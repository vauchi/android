// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vauchi.data.AuthenticationRequiredException
import com.vauchi.data.DeviceNotSecureException
import com.vauchi.data.ExchangeData
import com.vauchi.data.VauchiRepository
import com.vauchi.proximity.AudioMobileProximityHandler
import com.vauchi.proximity.AudioProximityService
import com.vauchi.ui.components.ProximityVerificationResult
import com.vauchi.ui.model.PasswordStrengthLevel
import com.vauchi.ui.model.PasswordStrengthResult
import com.vauchi.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_mobile.MobileApplyResult
import uniffi.vauchi_mobile.MobileConsentRecord
import uniffi.vauchi_mobile.MobileConsentType
import uniffi.vauchi_mobile.MobileContact
import uniffi.vauchi_mobile.MobileContactCard
import uniffi.vauchi_mobile.MobileDeletionInfo
import uniffi.vauchi_mobile.MobileDeletionState
import uniffi.vauchi_mobile.MobileDemoContact
import uniffi.vauchi_mobile.MobileDemoContactState
import uniffi.vauchi_mobile.MobileExchangeResult
import uniffi.vauchi_mobile.MobileFieldType
import uniffi.vauchi_mobile.MobileFieldValidation
import uniffi.vauchi_mobile.MobileGdprExport
import uniffi.vauchi_mobile.MobileProximityVerifier
import uniffi.vauchi_mobile.MobileRecoveryClaim
import uniffi.vauchi_mobile.MobileRecoveryProgress
import uniffi.vauchi_mobile.MobileRecoveryVoucher
import uniffi.vauchi_mobile.MobileSocialNetwork
import uniffi.vauchi_mobile.MobileSyncResult
import uniffi.vauchi_mobile.MobileUpdateStatus
import uniffi.vauchi_mobile.MobileValidationStatus
import uniffi.vauchi_mobile.MobileVisibilityLabel
import uniffi.vauchi_mobile.MobileVisibilityLabelDetail
import java.time.Instant

sealed class SyncState {
    object Idle : SyncState()

    object Syncing : SyncState()

    data class Success(
        val result: MobileSyncResult,
    ) : SyncState()

    data class Error(
        val message: String,
    ) : SyncState()
}

/**
 * State machine for the contact exchange flow with proximity verification.
 *
 * Flow: Idle -> PendingProximity (after QR scan) -> Completing (after proximity verified)
 *       -> Success | Failed
 *
 * Proximity verification is integrated at the protocol level via core's
 * `createQrExchange(handler)` which generates the challenge internally.
 */
sealed class ExchangeFlowState {
    /** No exchange in progress. */
    object Idle : ExchangeFlowState()

    /** QR scanned, waiting for proximity verification before completing exchange. */
    data class PendingProximity(
        val qrData: String,
        val challenge: ByteArray,
    ) : ExchangeFlowState()

    /** Proximity verified, exchange completing. */
    object Completing : ExchangeFlowState()

    /** Exchange completed successfully. */
    data class Success(
        val result: MobileExchangeResult,
    ) : ExchangeFlowState()

    /** Exchange failed. */
    data class Failed(
        val error: String,
    ) : ExchangeFlowState()
}

sealed class UiState {
    object Loading : UiState()

    object Setup : UiState()

    object Onboarding : UiState()

    data class Ready(
        val displayName: String,
        val publicId: String,
        val card: MobileContactCard,
        val contactCount: UInt,
    ) : UiState()

    data class Error(
        val message: String,
    ) : UiState()
}

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: VauchiRepository by lazy {
        VauchiRepository(application)
    }

    private val networkMonitor = NetworkMonitor(application)

    // Proximity verification
    private val proximityVerifier: MobileProximityVerifier by lazy {
        val audioHandler = AudioProximityService.getInstance(application)
        MobileProximityVerifier(audioHandler)
    }

    private val _proximitySupported = MutableStateFlow(false)
    val proximitySupported: StateFlow<Boolean> = _proximitySupported.asStateFlow()

    private val _proximityCapability = MutableStateFlow("none")
    val proximityCapability: StateFlow<String> = _proximityCapability.asStateFlow()

    // Exchange flow state (proximity verification gate)
    private val _exchangeState = MutableStateFlow<ExchangeFlowState>(ExchangeFlowState.Idle)
    val exchangeState: StateFlow<ExchangeFlowState> = _exchangeState.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Network connectivity state
    val isOnline: StateFlow<Boolean> =
        networkMonitor.isOnline
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Snackbar message channel for user feedback
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Sync state
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Last sync timestamp
    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()

    // Demo contact state (for users with no contacts)
    private val _demoContact = MutableStateFlow<MobileDemoContact?>(null)
    val demoContact: StateFlow<MobileDemoContact?> = _demoContact.asStateFlow()

    private val _demoContactState = MutableStateFlow<MobileDemoContactState?>(null)
    val demoContactState: StateFlow<MobileDemoContactState?> = _demoContactState.asStateFlow()

    // Visibility labels (for organizing contacts)
    // Based on: features/visibility_labels.feature
    private val _visibilityLabels = MutableStateFlow<List<MobileVisibilityLabel>>(emptyList())
    val visibilityLabels: StateFlow<List<MobileVisibilityLabel>> = _visibilityLabels.asStateFlow()

    private val _suggestedLabels = MutableStateFlow<List<String>>(emptyList())
    val suggestedLabels: StateFlow<List<String>> = _suggestedLabels.asStateFlow()

    // Accessibility settings
    private val _reduceMotion = MutableStateFlow(false)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val _highContrast = MutableStateFlow(false)
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    private val _largeTouchTargets = MutableStateFlow(false)
    val largeTouchTargets: StateFlow<Boolean> = _largeTouchTargets.asStateFlow()

    // Aha moments (progressive onboarding)
    private val _currentAhaMoment = MutableStateFlow<uniffi.vauchi_mobile.MobileAhaMoment?>(null)
    val currentAhaMoment: StateFlow<uniffi.vauchi_mobile.MobileAhaMoment?> = _currentAhaMoment.asStateFlow()

    // GDPR state
    private val _deletionState = MutableStateFlow<MobileDeletionInfo?>(null)
    val deletionState: StateFlow<MobileDeletionInfo?> = _deletionState.asStateFlow()

    private val _consentRecords = MutableStateFlow<List<MobileConsentRecord>>(emptyList())
    val consentRecords: StateFlow<List<MobileConsentRecord>> = _consentRecords.asStateFlow()

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    private fun showMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSyncState() {
        _syncState.value = SyncState.Idle
    }

    init {
        checkIdentity()
        initProximityVerification()
        loadAccessibilitySettingsSafely()
    }

    private fun loadAccessibilitySettingsSafely() {
        try {
            _reduceMotion.value = repository.getReduceMotion()
            _highContrast.value = repository.getHighContrast()
            _largeTouchTargets.value = repository.getLargeTouchTargets()
        } catch (_: Exception) {
            // Defaults already set; checkIdentity() will handle the error
        }
    }

    fun setReduceMotion(enabled: Boolean) {
        _reduceMotion.value = enabled
        repository.setReduceMotion(enabled)
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        repository.setHighContrast(enabled)
    }

    fun setLargeTouchTargets(enabled: Boolean) {
        _largeTouchTargets.value = enabled
        repository.setLargeTouchTargets(enabled)
    }

    private fun initProximityVerification() {
        _proximitySupported.value = proximityVerifier.isSupported()
        _proximityCapability.value = proximityVerifier.getCapability()
    }

    /** Emit a proximity challenge (for QR displayer) */
    fun emitProximityChallenge(challenge: ByteArray): Boolean {
        val result = proximityVerifier.emitChallenge(challenge)
        return result.success
    }

    /** Listen for proximity response (for QR scanner) */
    fun listenForProximityResponse(timeoutMs: ULong = 5000u): ByteArray? {
        val response = proximityVerifier.listenForResponse(timeoutMs)
        return if (response.isEmpty()) null else response
    }

    /** Stop any ongoing proximity verification */
    fun stopProximityVerification() {
        proximityVerifier.stop()
    }

    private fun checkIdentity() {
        viewModelScope.launch {
            try {
                val hasIdentity =
                    withContext(Dispatchers.IO) {
                        repository.hasIdentity()
                    }
                if (hasIdentity) {
                    // Existing user - auto-mark onboarding complete if not set
                    if (!repository.hasCompletedOnboarding()) {
                        repository.setOnboardingCompleted(true)
                    }
                    loadUserData()
                } else {
                    // New user - show onboarding flow
                    _uiState.value = UiState.Onboarding
                }
            } catch (e: DeviceNotSecureException) {
                _uiState.value =
                    UiState.Error(
                        e.message ?: "A secure lock screen is required to use Vauchi.",
                    )
            } catch (e: AuthenticationRequiredException) {
                _uiState.value =
                    UiState.Error(
                        "Your device needs to be unlocked to access your data. " +
                            "Please unlock your device and tap Retry.",
                    )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun completeOnboarding(
        displayName: String,
        phone: String?,
        email: String?,
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading
                withContext(Dispatchers.IO) {
                    // Create identity
                    repository.createIdentity(displayName)

                    // Add phone if provided
                    phone?.let {
                        repository.addField(MobileFieldType.PHONE, "Phone", it)
                    }

                    // Add email if provided
                    email?.let {
                        repository.addField(MobileFieldType.EMAIL, "Email", it)
                    }

                    // Mark onboarding complete
                    repository.setOnboardingCompleted(true)
                }
                loadUserData()
                // Initialize demo contact for new users
                initDemoContactIfNeeded()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to create identity")
            }
        }
    }

    fun createIdentity(displayName: String) {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading
                withContext(Dispatchers.IO) {
                    repository.createIdentity(displayName)
                }
                loadUserData()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to create identity")
            }
        }
    }

    private suspend fun loadUserData() {
        try {
            val (displayName, publicId, card, contactCount) =
                withContext(Dispatchers.IO) {
                    Tuple4(
                        repository.getDisplayName(),
                        repository.getPublicId(),
                        repository.getOwnCard(),
                        repository.contactCount(),
                    )
                }
            _uiState.value = UiState.Ready(displayName, publicId, card, contactCount)
            // Load demo contact state
            loadDemoContact()
        } catch (e: DeviceNotSecureException) {
            _uiState.value =
                UiState.Error(
                    e.message ?: "A secure lock screen is required to use Vauchi.",
                )
        } catch (e: AuthenticationRequiredException) {
            _uiState.value =
                UiState.Error(
                    "Your device needs to be unlocked to access your data. " +
                        "Please unlock your device and tap Retry.",
                )
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.message ?: "Failed to load user data")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadUserData()
        }
    }

    fun sync() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        repository.sync()
                    }
                _syncState.value = SyncState.Success(result)
                _lastSyncTime.value = Instant.now()
                loadUserData()
                val msg =
                    buildString {
                        append("Sync complete")
                        if (result.contactsAdded > 0u) append(" - ${result.contactsAdded} new contacts")
                        if (result.cardsUpdated > 0u) append(" - ${result.cardsUpdated} cards updated")
                    }
                showMessage(msg)
            } catch (e: Exception) {
                val errorMsg =
                    if (!networkMonitor.isCurrentlyConnected()) {
                        "No internet connection"
                    } else {
                        e.message ?: "Sync failed"
                    }
                _syncState.value = SyncState.Error(errorMsg)
                showMessage("Sync failed: $errorMsg")
            }
        }
    }

    fun getRelayUrl(): String = repository.getRelayUrl()

    fun setRelayUrl(url: String) {
        repository.setRelayUrl(url)
        showMessage("Relay URL updated (restart app to apply)")
    }

    fun addField(
        fieldType: MobileFieldType,
        label: String,
        value: String,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.addField(fieldType, label, value)
                }
                loadUserData()
                showMessage("Field added")
            } catch (e: Exception) {
                showMessage("Failed to add field: ${e.message}")
            }
        }
    }

    fun updateField(
        label: String,
        newValue: String,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.updateField(label, newValue)
                }
                loadUserData()
                showMessage("Field updated")
            } catch (e: Exception) {
                showMessage("Failed to update field: ${e.message}")
            }
        }
    }

    fun removeField(label: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.removeField(label)
                }
                loadUserData()
                showMessage("Field removed")
            } catch (e: Exception) {
                showMessage("Failed to remove field: ${e.message}")
            }
        }
    }

    suspend fun generateExchangeQr(): ExchangeData? =
        try {
            withContext(Dispatchers.IO) {
                repository.generateExchangeQr()
            }
        } catch (e: Exception) {
            null
        }

    suspend fun completeExchange(qrData: String): MobileExchangeResult? =
        try {
            val result =
                withContext(Dispatchers.IO) {
                    repository.completeExchange(qrData)
                }
            loadUserData()
            // Auto-remove demo contact after first real exchange
            if (result.success) {
                autoRemoveDemoContact()
            }
            result
        } catch (e: Exception) {
            null
        }

    /**
     * Begin the exchange flow with proximity verification.
     * Called after QR code is scanned. Transitions to PendingProximity state
     * where the UI shows a proximity confirmation gate.
     *
     * The actual proximity verification happens at the protocol level when
     * [completeExchangeAfterProximity] creates a proximity exchange session.
     */
    fun startExchangeWithProximity(qrData: String) {
        _exchangeState.value = ExchangeFlowState.PendingProximity(qrData, ByteArray(0))
    }

    /**
     * Complete the exchange after proximity has been verified.
     * Must only be called when exchangeState is PendingProximity.
     *
     * Uses core's proximity exchange session which handles proximity
     * verification at the protocol level via [AudioMobileProximityHandler].
     */
    suspend fun completeExchangeAfterProximity() {
        val state = _exchangeState.value
        if (state !is ExchangeFlowState.PendingProximity) return
        _exchangeState.value = ExchangeFlowState.Completing
        val result =
            try {
                val audioService = AudioProximityService.getInstance(getApplication())
                val verifier = MobileProximityVerifier(audioService)
                val handler = AudioMobileProximityHandler(verifier)
                val exchangeResult =
                    withContext(Dispatchers.IO) {
                        repository.completeExchangeWithProximity(state.qrData, handler)
                    }
                loadUserData()
                if (exchangeResult.success) {
                    autoRemoveDemoContact()
                }
                exchangeResult
            } catch (e: Exception) {
                null
            }
        if (result != null && result.success) {
            _exchangeState.value = ExchangeFlowState.Success(result)
        } else {
            _exchangeState.value =
                ExchangeFlowState.Failed(
                    result?.errorMessage ?: "Exchange failed",
                )
        }
    }

    /**
     * Cancel the proximity verification and reset exchange state.
     */
    fun cancelExchangeProximity() {
        stopProximityVerification()
        _exchangeState.value = ExchangeFlowState.Idle
    }

    /**
     * Reset exchange state back to idle (e.g., after success/failure acknowledged).
     */
    fun resetExchangeState() {
        _exchangeState.value = ExchangeFlowState.Idle
    }

    suspend fun listContacts(): List<MobileContact> =
        try {
            withContext(Dispatchers.IO) {
                repository.listContacts()
            }
        } catch (e: Exception) {
            emptyList()
        }

    suspend fun listContactsPaginated(
        offset: UInt,
        limit: UInt,
    ): List<MobileContact> =
        withContext(Dispatchers.IO) {
            repository.listContactsPaginated(offset, limit)
        }

    suspend fun searchContacts(query: String): List<MobileContact> =
        withContext(Dispatchers.IO) {
            repository.searchContacts(query)
        }

    fun removeContact(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.removeContact(id)
                }
                loadUserData()
                showMessage("Contact removed")
            } catch (e: Exception) {
                showMessage("Failed to remove contact: ${e.message}")
            }
        }
    }

    suspend fun getContact(id: String): MobileContact? =
        try {
            withContext(Dispatchers.IO) {
                repository.getContact(id)
            }
        } catch (e: Exception) {
            null
        }

    suspend fun verifyContact(id: String): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.verifyContact(id)
            }
            showMessage("Contact verified successfully")
            true
        } catch (e: Exception) {
            showMessage("Failed to verify contact: ${e.message}")
            false
        }

    suspend fun trustContactForRecovery(id: String): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.trustContactForRecovery(id)
            }
            showMessage("Contact trusted for recovery")
            true
        } catch (e: Exception) {
            showMessage("Failed to trust contact: ${e.message}")
            false
        }

    suspend fun untrustContactForRecovery(id: String): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.untrustContactForRecovery(id)
            }
            showMessage("Recovery trust removed")
            true
        } catch (e: Exception) {
            showMessage("Failed to remove trust: ${e.message}")
            false
        }

    suspend fun trustedContactCount(): UInt =
        try {
            withContext(Dispatchers.IO) {
                repository.trustedContactCount()
            }
        } catch (e: Exception) {
            0u
        }

    suspend fun getOwnPublicKey(): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.getPublicKey()
            }
        } catch (e: Exception) {
            null
        }

    suspend fun getOwnFingerprint(): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.getOwnFingerprint()
            }
        } catch (e: Exception) {
            null
        }

    suspend fun getOwnCard(): MobileContactCard? =
        try {
            withContext(Dispatchers.IO) {
                repository.getOwnCard()
            }
        } catch (e: Exception) {
            null
        }

    fun setFieldVisibility(
        contactId: String,
        fieldLabel: String,
        visible: Boolean,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (visible) {
                        repository.showFieldToContact(contactId, fieldLabel)
                    } else {
                        repository.hideFieldFromContact(contactId, fieldLabel)
                    }
                }
                showMessage(if (visible) "Field shown to contact" else "Field hidden from contact")
            } catch (e: Exception) {
                showMessage("Failed to update visibility: ${e.message}")
            }
        }
    }

    suspend fun isFieldVisibleToContact(
        contactId: String,
        fieldLabel: String,
    ): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.isFieldVisibleToContact(contactId, fieldLabel)
            }
        } catch (e: Exception) {
            true // Default to visible on error
        }

    // MARK: - Field Validation

    suspend fun getFieldValidationStatus(
        contactId: String,
        fieldId: String,
        fieldValue: String,
    ) = withContext(Dispatchers.IO) {
        repository.getFieldValidationStatus(contactId, fieldId, fieldValue)
    }

    suspend fun validateField(
        contactId: String,
        fieldId: String,
        fieldValue: String,
    ) = withContext(Dispatchers.IO) {
        repository.validateField(contactId, fieldId, fieldValue)
    }

    suspend fun revokeFieldValidation(
        contactId: String,
        fieldId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            repository.revokeFieldValidation(contactId, fieldId)
        }

    suspend fun getFieldValidationCount(
        contactId: String,
        fieldId: String,
    ): UInt =
        withContext(Dispatchers.IO) {
            repository.getFieldValidationCount(contactId, fieldId)
        }

    suspend fun exportBackup(password: String): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.exportBackup(password)
            }
        } catch (e: Exception) {
            null
        }

    suspend fun importBackup(
        backupData: String,
        password: String,
    ): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.importBackup(backupData, password)
            }
            loadUserData()
            true
        } catch (e: Exception) {
            false
        }

    fun checkPasswordStrength(password: String): PasswordStrengthResult =
        try {
            val check = repository.checkPasswordStrength(password)
            PasswordStrengthResult(
                level =
                    when (check.strength) {
                        uniffi.vauchi_mobile.MobilePasswordStrength.TOO_WEAK -> PasswordStrengthLevel.TooWeak
                        uniffi.vauchi_mobile.MobilePasswordStrength.FAIR -> PasswordStrengthLevel.Fair
                        uniffi.vauchi_mobile.MobilePasswordStrength.STRONG -> PasswordStrengthLevel.Strong
                        uniffi.vauchi_mobile.MobilePasswordStrength.VERY_STRONG -> PasswordStrengthLevel.VeryStrong
                    },
                description = check.description,
                feedback = check.feedback,
                isAcceptable = check.isAcceptable,
            )
        } catch (e: Exception) {
            PasswordStrengthResult()
        }

    // Social network operations
    fun listSocialNetworks(): List<MobileSocialNetwork> =
        try {
            repository.listSocialNetworks()
        } catch (e: Exception) {
            emptyList()
        }

    fun searchSocialNetworks(query: String): List<MobileSocialNetwork> =
        try {
            repository.searchSocialNetworks(query)
        } catch (e: Exception) {
            emptyList()
        }

    fun getProfileUrl(
        networkId: String,
        username: String,
    ): String? =
        try {
            repository.getProfileUrl(networkId, username)
        } catch (e: Exception) {
            null
        }

    // Content Updates operations
    fun isContentUpdatesSupported(): Boolean =
        try {
            repository.isContentUpdatesSupported()
        } catch (e: Exception) {
            false
        }

    suspend fun checkContentUpdates(): MobileUpdateStatus? =
        try {
            withContext(Dispatchers.IO) {
                repository.checkContentUpdates()
            }
        } catch (e: Exception) {
            showMessage("Failed to check updates: ${e.message}")
            null
        }

    suspend fun applyContentUpdates(): MobileApplyResult? =
        try {
            withContext(Dispatchers.IO) {
                repository.applyContentUpdates()
            }
        } catch (e: Exception) {
            showMessage("Failed to apply updates: ${e.message}")
            null
        }

    fun reloadSocialNetworks() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.reloadSocialNetworks()
                }
            } catch (e: Exception) {
                // Silently fail - networks will reload on next access
            }
        }
    }

    // Aha Moments operations (Progressive Onboarding)
    fun tryTriggerAhaMoment(momentType: uniffi.vauchi_mobile.MobileAhaMomentType) {
        viewModelScope.launch {
            try {
                val moment =
                    withContext(Dispatchers.IO) {
                        repository.tryTriggerAhaMoment(momentType)
                    }
                _currentAhaMoment.value = moment
            } catch (e: Exception) {
                // Silently fail - aha moments are non-critical
            }
        }
    }

    fun tryTriggerAhaMomentWithContext(
        momentType: uniffi.vauchi_mobile.MobileAhaMomentType,
        context: String,
    ) {
        viewModelScope.launch {
            try {
                val moment =
                    withContext(Dispatchers.IO) {
                        repository.tryTriggerAhaMomentWithContext(momentType, context)
                    }
                _currentAhaMoment.value = moment
            } catch (e: Exception) {
                // Silently fail - aha moments are non-critical
            }
        }
    }

    fun dismissAhaMoment() {
        _currentAhaMoment.value = null
    }

    fun hasSeenAhaMoment(momentType: uniffi.vauchi_mobile.MobileAhaMomentType): Boolean =
        try {
            repository.hasSeenAhaMoment(momentType)
        } catch (e: Exception) {
            true // Default to "seen" on error to avoid repeated triggers
        }

    fun ahaMomentsProgress(): Pair<Int, Int> =
        try {
            Pair(repository.ahaMomentsSeenCount().toInt(), repository.ahaMomentsTotalCount().toInt())
        } catch (e: Exception) {
            Pair(0, 0)
        }

    fun resetAhaMoments() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.resetAhaMoments()
                }
                showMessage("Tips reset")
            } catch (e: Exception) {
                showMessage("Failed to reset tips: ${e.message}")
            }
        }
    }

    // Certificate Pinning operations
    fun isCertificatePinningEnabled(): Boolean =
        try {
            repository.isCertificatePinningEnabled()
        } catch (e: Exception) {
            false
        }

    fun setPinnedCertificate(certPem: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setPinnedCertificate(certPem)
                }
                showMessage("Certificate pinning updated")
            } catch (e: Exception) {
                showMessage("Failed to set certificate: ${e.message}")
            }
        }
    }

    // Duress PIN operations
    private val _isDuressEnabled = MutableStateFlow(false)
    val isDuressEnabled: StateFlow<Boolean> = _isDuressEnabled.asStateFlow()

    fun loadDuressStatus() {
        _isDuressEnabled.value =
            try {
                repository.isDuressEnabled()
            } catch (e: Exception) {
                false
            }
    }

    fun setupDuressPassword(pin: String) {
        viewModelScope.launch {
            try {
                withContext<Unit>(Dispatchers.IO) {
                    repository.setupDuressPassword(pin)
                }
                _isDuressEnabled.value = true
                showMessage("Duress PIN configured")
            } catch (e: Exception) {
                showMessage("Failed to set duress PIN: ${e.message}")
            }
        }
    }

    fun disableDuress() {
        viewModelScope.launch {
            try {
                withContext<Unit>(Dispatchers.IO) {
                    repository.disableDuress()
                }
                _isDuressEnabled.value = false
                showMessage("Duress PIN disabled")
            } catch (e: Exception) {
                showMessage("Failed to disable duress PIN: ${e.message}")
            }
        }
    }

    // Panic Shred operations
    fun panicShred() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.panicShred()
                }
                showMessage("Emergency shred complete. All data destroyed.")
            } catch (e: Exception) {
                showMessage("Failed to shred: ${e.message}")
            }
        }
    }

    // Tor Mode state
    private val _isTorEnabled = MutableStateFlow(false)
    val isTorEnabled: StateFlow<Boolean> = _isTorEnabled.asStateFlow()

    private val _torPreferOnion = MutableStateFlow(true)
    val torPreferOnion: StateFlow<Boolean> = _torPreferOnion.asStateFlow()

    private val _torBridges = MutableStateFlow<List<String>>(emptyList())
    val torBridges: StateFlow<List<String>> = _torBridges.asStateFlow()

    // Emergency Broadcast operations
    private val _emergencyConfigured = MutableStateFlow(false)
    val emergencyConfigured: StateFlow<Boolean> = _emergencyConfigured.asStateFlow()

    fun loadEmergencyConfig() {
        _emergencyConfigured.value =
            try {
                repository.getEmergencyConfig() != null
            } catch (e: Exception) {
                false
            }
    }

    fun configureEmergencyBroadcast(
        contactIds: List<String>,
        message: String,
        includeLocation: Boolean,
    ) {
        viewModelScope.launch {
            try {
                withContext<Unit>(Dispatchers.IO) {
                    repository.configureEmergencyBroadcast(contactIds, message, includeLocation)
                }
                _emergencyConfigured.value = true
                showMessage("Emergency broadcast configured")
            } catch (e: Exception) {
                showMessage("Failed to configure: ${e.message}")
            }
        }
    }

    fun sendEmergencyBroadcast() {
        viewModelScope.launch {
            try {
                withContext<Unit>(Dispatchers.IO) {
                    repository.sendEmergencyBroadcast()
                }
                showMessage("Emergency broadcast sent")
            } catch (e: Exception) {
                showMessage("Failed to send: ${e.message}")
            }
        }
    }

    fun disableEmergencyBroadcast() {
        viewModelScope.launch {
            try {
                withContext<Unit>(Dispatchers.IO) {
                    repository.disableEmergencyBroadcast()
                }
                _emergencyConfigured.value = false
                showMessage("Emergency broadcast disabled")
            } catch (e: Exception) {
                showMessage("Failed to disable: ${e.message}")
            }
        }
    }

    // Tor Mode operations
    fun loadTorConfig() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val (enabled, bridges, preferOnion) = repository.getTorConfig()
                    _isTorEnabled.value = enabled
                    _torBridges.value = bridges
                    _torPreferOnion.value = preferOnion
                } catch (e: Exception) {
                    // Config not available yet
                }
            }
        }
    }

    fun saveTorConfig(
        enabled: Boolean,
        bridges: List<String>,
        preferOnion: Boolean,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    repository.saveTorConfig(enabled, bridges, preferOnion)
                    _isTorEnabled.value = enabled
                    _torBridges.value = bridges
                    _torPreferOnion.value = preferOnion
                } catch (e: Exception) {
                    // Save failed - bindings not yet available
                }
            }
        }
    }

    // Recovery operations
    suspend fun createRecoveryClaim(oldPkHex: String): MobileRecoveryClaim? =
        try {
            withContext(Dispatchers.IO) {
                repository.createRecoveryClaim(oldPkHex)
            }
        } catch (e: Exception) {
            showMessage("Failed to create claim: ${e.message}")
            null
        }

    suspend fun parseRecoveryClaim(claimB64: String): MobileRecoveryClaim? =
        try {
            withContext(Dispatchers.IO) {
                repository.parseRecoveryClaim(claimB64)
            }
        } catch (e: Exception) {
            showMessage("Invalid claim data: ${e.message}")
            null
        }

    suspend fun createRecoveryVoucher(claimB64: String): MobileRecoveryVoucher? =
        try {
            withContext(Dispatchers.IO) {
                repository.createRecoveryVoucher(claimB64)
            }
        } catch (e: Exception) {
            showMessage("Failed to create voucher: ${e.message}")
            null
        }

    suspend fun addRecoveryVoucher(voucherB64: String): MobileRecoveryProgress? =
        try {
            withContext(Dispatchers.IO) {
                repository.addRecoveryVoucher(voucherB64)
            }
        } catch (e: Exception) {
            showMessage("Failed to add voucher: ${e.message}")
            null
        }

    suspend fun getRecoveryStatus(): MobileRecoveryProgress? =
        try {
            withContext(Dispatchers.IO) {
                repository.getRecoveryStatus()
            }
        } catch (e: Exception) {
            null
        }

    suspend fun getRecoveryProof(): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.getRecoveryProof()
            }
        } catch (e: Exception) {
            null
        }

    // Demo contact operations
    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     */
    fun initDemoContactIfNeeded() {
        viewModelScope.launch {
            try {
                val demo =
                    withContext(Dispatchers.IO) {
                        repository.initDemoContactIfNeeded()
                    }
                _demoContact.value = demo
                _demoContactState.value = repository.getDemoContactState()
            } catch (e: Exception) {
                // Silently fail - demo is optional
            }
        }
    }

    /**
     * Load the current demo contact state
     */
    fun loadDemoContact() {
        viewModelScope.launch {
            try {
                val demo =
                    withContext(Dispatchers.IO) {
                        repository.getDemoContact()
                    }
                _demoContact.value = demo
                _demoContactState.value = repository.getDemoContactState()
            } catch (e: Exception) {
                _demoContact.value = null
                _demoContactState.value = repository.getDemoContactState()
            }
        }
    }

    /**
     * Dismiss the demo contact manually
     */
    fun dismissDemoContact() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.dismissDemoContact()
                }
                _demoContact.value = null
                _demoContactState.value = repository.getDemoContactState()
            } catch (e: Exception) {
                showMessage("Failed to dismiss demo: ${e.message}")
            }
        }
    }

    /**
     * Auto-remove demo contact after first real exchange.
     * Called automatically by completeExchange().
     */
    private fun autoRemoveDemoContact() {
        viewModelScope.launch {
            try {
                val removed =
                    withContext(Dispatchers.IO) {
                        repository.autoRemoveDemoContact()
                    }
                if (removed) {
                    _demoContact.value = null
                    _demoContactState.value = repository.getDemoContactState()
                }
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    /**
     * Restore the demo contact from Settings
     */
    fun restoreDemoContact() {
        viewModelScope.launch {
            try {
                val demo =
                    withContext(Dispatchers.IO) {
                        repository.restoreDemoContact()
                    }
                _demoContact.value = demo
                _demoContactState.value = repository.getDemoContactState()
                showMessage("Demo contact restored")
            } catch (e: Exception) {
                showMessage("Failed to restore demo: ${e.message}")
            }
        }
    }

    /**
     * Trigger a demo update
     */
    fun triggerDemoUpdate() {
        viewModelScope.launch {
            try {
                val demo =
                    withContext(Dispatchers.IO) {
                        repository.triggerDemoUpdate()
                    }
                _demoContact.value = demo
                _demoContactState.value = repository.getDemoContactState()
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    /**
     * Check if demo update is available
     */
    fun isDemoUpdateAvailable(): Boolean =
        try {
            repository.isDemoUpdateAvailable()
        } catch (e: Exception) {
            false
        }

    // MARK: - Visibility Labels
    // Based on: features/visibility_labels.feature

    /**
     * Load all visibility labels
     */
    fun loadLabels() {
        viewModelScope.launch {
            try {
                val labels =
                    withContext(Dispatchers.IO) {
                        repository.listLabels()
                    }
                _visibilityLabels.value = labels
                _suggestedLabels.value = repository.getSuggestedLabels()
            } catch (e: Exception) {
                _visibilityLabels.value = emptyList()
            }
        }
    }

    /**
     * Create a new visibility label
     */
    fun createLabel(
        name: String,
        onSuccess: (MobileVisibilityLabel) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val label =
                    withContext(Dispatchers.IO) {
                        repository.createLabel(name)
                    }
                loadLabels()
                onSuccess(label)
                showMessage("Label \"$name\" created")
            } catch (e: Exception) {
                onError(e.message ?: "Failed to create label")
                showMessage("Failed to create label: ${e.message}")
            }
        }
    }

    /**
     * Get label details
     */
    fun getLabel(labelId: String): MobileVisibilityLabelDetail? =
        try {
            repository.getLabel(labelId)
        } catch (e: Exception) {
            null
        }

    /**
     * Rename a visibility label
     */
    fun renameLabel(
        labelId: String,
        newName: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.renameLabel(labelId, newName)
                }
                loadLabels()
                onSuccess()
                showMessage("Label renamed to \"$newName\"")
            } catch (e: Exception) {
                onError(e.message ?: "Failed to rename label")
                showMessage("Failed to rename label: ${e.message}")
            }
        }
    }

    /**
     * Delete a visibility label
     */
    fun deleteLabel(
        labelId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.deleteLabel(labelId)
                }
                loadLabels()
                onSuccess()
                showMessage("Label deleted")
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete label")
                showMessage("Failed to delete label: ${e.message}")
            }
        }
    }

    /**
     * Add contact to a label
     */
    fun addContactToLabel(
        labelId: String,
        contactId: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.addContactToLabel(labelId, contactId)
                }
                loadLabels()
                onSuccess()
            } catch (e: Exception) {
                showMessage("Failed to add contact to label: ${e.message}")
            }
        }
    }

    /**
     * Remove contact from a label
     */
    fun removeContactFromLabel(
        labelId: String,
        contactId: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.removeContactFromLabel(labelId, contactId)
                }
                loadLabels()
                onSuccess()
            } catch (e: Exception) {
                showMessage("Failed to remove contact from label: ${e.message}")
            }
        }
    }

    /**
     * Get all labels for a contact
     */
    fun getLabelsForContact(contactId: String): List<MobileVisibilityLabel> =
        try {
            repository.getLabelsForContact(contactId)
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Set field visibility for a label
     */
    fun setLabelFieldVisibility(
        labelId: String,
        fieldId: String,
        visible: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setLabelFieldVisibility(labelId, fieldId, visible)
                }
                loadLabels()
                onSuccess()
            } catch (e: Exception) {
                showMessage("Failed to update field visibility: ${e.message}")
            }
        }
    }

    // MARK: - Device Management
    // Based on: features/device_management.feature

    /**
     * Get list of linked devices
     */
    fun getDevices() = repository.getDevices()

    /**
     * Generate device link QR code for a new device to scan
     */
    fun generateDeviceLinkQr() = repository.generateDeviceLinkQr()

    /**
     * Parse device link QR code
     */
    fun parseDeviceLinkQr(qrData: String) = repository.parseDeviceLinkQr(qrData)

    /**
     * Get the number of linked devices
     */
    fun deviceCount(): UInt = repository.deviceCount()

    /**
     * Unlink a device by index
     */
    fun unlinkDevice(deviceIndex: UInt): Boolean = repository.unlinkDevice(deviceIndex)

    /**
     * Check if this is the primary device
     */
    fun isPrimaryDevice(): Boolean = repository.isPrimaryDevice()

    // MARK: - Device Linking Protocol

    sealed class DeviceLinkState {
        object Idle : DeviceLinkState()

        object GeneratingQR : DeviceLinkState()

        data class WaitingForRequest(
            val qrData: String,
        ) : DeviceLinkState()

        data class ConfirmingDevice(
            val deviceName: String,
            val confirmationCode: String,
            val challenge: ByteArray,
        ) : DeviceLinkState()

        data class VerifyingProximity(
            val challenge: ByteArray,
            val confirmationCode: String,
        ) : DeviceLinkState()

        object Completing : DeviceLinkState()

        object Success : DeviceLinkState()

        data class Failed(
            val error: String,
        ) : DeviceLinkState()
    }

    private val _deviceLinkState = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)
    val deviceLinkState: StateFlow<DeviceLinkState> = _deviceLinkState.asStateFlow()

    // The initiator state machine from the device link protocol.
    // Typed as Any because MobileDeviceLinkInitiator may not be available in current bindings.
    private var currentInitiator: Any? = null
    private var currentSenderToken: String? = null

    /**
     * Start the device link protocol as initiator.
     * Generates QR data for the new device to scan.
     */
    suspend fun startDeviceLinkInitiator(): String? {
        _deviceLinkState.value = DeviceLinkState.GeneratingQR
        return try {
            val qrData =
                withContext(Dispatchers.IO) {
                    val linkData = repository.generateDeviceLinkQr()
                    linkData.qrData
                }
            _deviceLinkState.value = DeviceLinkState.WaitingForRequest(qrData)
            qrData
        } catch (e: Exception) {
            _deviceLinkState.value = DeviceLinkState.Failed(e.message ?: "Failed to generate QR")
            null
        }
    }

    /**
     * Listen for an incoming device link request from the new device via relay.
     * This is called after displaying the QR code.
     */
    suspend fun listenForDeviceLinkRequest() {
        try {
            val request =
                withContext(Dispatchers.IO) {
                    repository.listenForDeviceLinkRequest(300u)
                }
            // NOTE: When real bindings are available, extract fields from request:
            // currentSenderToken = request.senderToken
            // val confirmation = currentInitiator.prepareConfirmation(request.encryptedPayload)
            // val challenge = currentInitiator.proximityChallenge().toByteArray()
            // _deviceLinkState.value = DeviceLinkState.ConfirmingDevice(
            //     deviceName = confirmation.deviceName,
            //     confirmationCode = confirmation.confirmationCode,
            //     challenge = challenge
            // )

            // For now, this will throw UnsupportedOperationException from the stub
            _deviceLinkState.value = DeviceLinkState.Failed("Relay transport not yet available")
        } catch (e: Exception) {
            _deviceLinkState.value = DeviceLinkState.Failed(e.message ?: "Failed to listen for request")
        }
    }

    /**
     * Approve the device link after proximity verification.
     *
     * @param verificationResult The proximity proof from the verification step.
     */
    suspend fun approveDeviceLink(verificationResult: ProximityVerificationResult) {
        _deviceLinkState.value = DeviceLinkState.Completing
        try {
            // NOTE: When real bindings are available, construct proof and call:
            // val initiator = currentInitiator as MobileDeviceLinkInitiator
            // val senderToken = currentSenderToken ?: throw IllegalStateException("No sender token")
            // val proof = when (verificationResult) {
            //     is ProximityVerificationResult.Ultrasonic -> MobileProximityProof.Ultrasonic(
            //         challengeResponse = verificationResult.challengeResponse.toList(),
            //         verifiedAt = verificationResult.verifiedAt,
            //     )
            //     is ProximityVerificationResult.Manual -> MobileProximityProof.ManualConfirmation(
            //         confirmationCode = verificationResult.confirmationCode,
            //         confirmedAt = verificationResult.confirmedAt,
            //     )
            // }
            // val result = initiator.confirmLink(proof)
            // result.encryptedResponse?.let { responseBytes ->
            //     withContext(Dispatchers.IO) {
            //         repository.sendDeviceLinkResponse(senderToken, responseBytes.toByteArray())
            //     }
            // }

            _deviceLinkState.value = DeviceLinkState.Success
            currentInitiator = null
            currentSenderToken = null
        } catch (e: Exception) {
            _deviceLinkState.value = DeviceLinkState.Failed(e.message ?: "Failed to complete link")
        }
    }

    /**
     * Cancel the device link protocol.
     */
    fun cancelDeviceLink() {
        _deviceLinkState.value = DeviceLinkState.Idle
        currentInitiator = null
        currentSenderToken = null
    }

    // MARK: - GDPR Operations

    /** Export all user data as GDPR JSON. */
    fun exportGdprData(): MobileGdprExport? =
        try {
            repository.exportGdprData()
        } catch (e: Exception) {
            showMessage("Export failed: ${e.message}")
            null
        }

    /** Schedule account deletion with 7-day grace period. */
    fun scheduleAccountDeletion() {
        viewModelScope.launch {
            try {
                val info =
                    withContext(Dispatchers.IO) {
                        repository.scheduleAccountDeletion()
                    }
                _deletionState.value = info
            } catch (e: Exception) {
                showMessage("Schedule failed: ${e.message}")
            }
        }
    }

    /** Cancel a scheduled account deletion. */
    fun cancelAccountDeletion() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.cancelAccountDeletion()
                }
                _deletionState.value =
                    withContext(Dispatchers.IO) {
                        repository.getDeletionState()
                    }
            } catch (e: Exception) {
                showMessage("Cancel failed: ${e.message}")
            }
        }
    }

    /** Load current deletion state. */
    fun loadDeletionState() {
        viewModelScope.launch {
            try {
                _deletionState.value =
                    withContext(Dispatchers.IO) {
                        repository.getDeletionState()
                    }
            } catch (e: Exception) {
                // Silently handle — state stays null
            }
        }
    }

    /** Grant consent for a type. */
    fun grantConsent(type: MobileConsentType) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.grantConsent(type)
                }
                loadConsentRecords()
            } catch (e: Exception) {
                showMessage("Grant failed: ${e.message}")
            }
        }
    }

    /** Revoke consent for a type. */
    fun revokeConsent(type: MobileConsentType) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.revokeConsent(type)
                }
                loadConsentRecords()
            } catch (e: Exception) {
                showMessage("Revoke failed: ${e.message}")
            }
        }
    }

    /** Load all consent records. */
    fun loadConsentRecords() {
        viewModelScope.launch {
            try {
                _consentRecords.value =
                    withContext(Dispatchers.IO) {
                        repository.getConsentRecords()
                    }
            } catch (e: Exception) {
                // Silently handle
            }
        }
    }
}

private data class Tuple4<A, B, C, D>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
)
