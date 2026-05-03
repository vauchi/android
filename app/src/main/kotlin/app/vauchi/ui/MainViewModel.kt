// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.app.Application
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vauchi.data.AuthenticationRequiredException
import app.vauchi.data.DeviceNotSecureException
import app.vauchi.data.ExchangeSessionData
import app.vauchi.data.VauchiRepository
import app.vauchi.util.LocalizationManager
import app.vauchi.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.DeviceLinkSessionListener
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileBiometricUnlockOutcome
import uniffi.vauchi_platform.MobileConsentRecord
import uniffi.vauchi_platform.MobileConsentType
import uniffi.vauchi_platform.MobileContact
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileDeletionInfo
import uniffi.vauchi_platform.MobileDeletionState
import uniffi.vauchi_platform.MobileDemoContact
import uniffi.vauchi_platform.MobileDemoContactState
import uniffi.vauchi_platform.MobileDeviceLinkSession
import uniffi.vauchi_platform.MobileDuplicatePair
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileGdprExport
import uniffi.vauchi_platform.MobileRecoveryClaim
import uniffi.vauchi_platform.MobileRecoveryProgress
import uniffi.vauchi_platform.MobileRecoveryVoucher
import uniffi.vauchi_platform.MobileSocialNetwork
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.MobileUpdateStatus
import uniffi.vauchi_platform.MobileVisibilityLabel
import uniffi.vauchi_platform.MobileVisibilityLabelDetail
import uniffi.vauchi_platform.PlatformAppEngine
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

    data class RateLimited(
        val retryAfterSecs: Long,
    ) : SyncState()
}

sealed class UiState {
    object Loading : UiState()

    object Onboarding : UiState()

    /** Device needs biometric/PIN authentication to access KeyStore keys. */
    object AuthRequired : UiState()

    /** Biometric OK but duress is enabled — show app password screen. */
    object AppPasswordRequired : UiState()

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

    val appEngine: PlatformAppEngine
        get() = repository.appEngine

    private val localizationManager = LocalizationManager.getInstance(application)
    private val networkMonitor = NetworkMonitor(application)

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
    private val _currentAhaMoment = MutableStateFlow<uniffi.vauchi_platform.MobileAhaMoment?>(null)
    val currentAhaMoment: StateFlow<uniffi.vauchi_platform.MobileAhaMoment?> = _currentAhaMoment.asStateFlow()

    // GDPR state
    private val _deletionState = MutableStateFlow<MobileDeletionInfo?>(null)
    val deletionState: StateFlow<MobileDeletionInfo?> = _deletionState.asStateFlow()

    private val _consentRecords = MutableStateFlow<List<MobileConsentRecord>>(emptyList())
    val consentRecords: StateFlow<List<MobileConsentRecord>> = _consentRecords.asStateFlow()

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSyncState() {
        _syncState.value = SyncState.Idle
    }

    init {
        checkIdentity()
        loadAccessibilitySettingsSafely()
        observeNetworkStateForCore()
    }

    /**
     * Forward `NetworkMonitor` reachability into core so the
     * offline `Component::Banner` is injected into every emitted
     * `ScreenModel` while offline (audit
     * `2026-04-28-lifecycle-session-residue-umbrella` P2-D).
     * The frontend keeps `isOnline` for legacy collectors but the
     * banner-render decision lives in core.
     */
    private fun observeNetworkStateForCore() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                try {
                    appEngine.setNetworkOnline(online)
                } catch (e: Exception) {
                    Log.w("Vauchi", "setNetworkOnline failed: ${e.message}")
                }
            }
        }
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

    private fun checkIdentity() {
        viewModelScope.launch {
            try {
                // Pre-check: verify biometric/credential authentication is possible
                // before attempting any KeyStore operations. This prevents users on
                // devices without a lock screen from hitting a silent BiometricPrompt
                // failure loop (T1-8).
                val biometricManager = BiometricManager.from(getApplication())
                val canAuth =
                    biometricManager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    _uiState.value =
                        UiState.Error(
                            "A device lock screen (PIN, pattern, or biometric) is required to use Vauchi. " +
                                "Please set one up in Settings.",
                        )
                    return@launch
                }

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
                android.util.Log.e("Vauchi", "checkIdentity: auth required", e)
                _uiState.value = UiState.AuthRequired
            } catch (e: Exception) {
                android.util.Log.e("Vauchi", "checkIdentity: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Called when core-driven onboarding completes — creates the identity from collected data. */
    fun onCoreOnboardingComplete(displayName: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val name = displayName ?: "User"
                    repository.createIdentity(name)
                    repository.setOnboardingCompleted(true)
                }
                loadUserData()
                initDemoContactIfNeeded()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to complete onboarding")
            }
        }
    }

    /** Create test identity for --reset-for-testing (DEBUG only). */
    fun seedTestIdentityIfNeeded() {
        if (!repository.hasIdentity()) {
            Log.i("Vauchi", "--reset-for-testing: creating test identity")
            createIdentity("Test User")
        } else {
            Log.i("Vauchi", "--reset-for-testing: identity already exists")
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
            android.util.Log.e("Vauchi", "loadUserData: auth required", e)
            _uiState.value = UiState.AuthRequired
        } catch (e: Exception) {
            android.util.Log.e("Vauchi", "loadUserData: ${e.javaClass.simpleName}: ${e.message}", e)
            _uiState.value = UiState.Error(e.message ?: "Failed to load user data")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadUserData()
        }
    }

    /** Re-run full initialization (identity check + load). Use after biometric auth. */
    fun retryInit() {
        viewModelScope.launch {
            // Core owns the post-biometric duress decision and the
            // 300 ms constant-time floor that hides whether duress
            // is configured (audit
            // `2026-04-28-lifecycle-session-residue-umbrella` P2-B).
            // The call sleeps in Rust for ≥
            // BIOMETRIC_UNLOCK_MIN_DURATION, so dispatch off the main
            // thread.
            val outcome =
                try {
                    withContext(Dispatchers.IO) { appEngine.biometricUnlockCheck() }
                } catch (_: Exception) {
                    null
                }

            when (outcome) {
                MobileBiometricUnlockOutcome.PROMPT_FOR_DURESS_PIN -> {
                    _uiState.value = UiState.AppPasswordRequired
                }

                MobileBiometricUnlockOutcome.UNLOCKED, null -> {
                    checkIdentity()
                }
            }
        }
    }

    /** Return to biometric screen (cancel app password entry). */
    fun cancelAppPassword() {
        _uiState.value = UiState.AuthRequired
    }

    /**
     * Called from AppPasswordScreen after user enters their app PIN.
     * Routes through core.authenticate() which sets auth_mode
     * (Normal or Duress) based on which PIN was entered.
     */
    fun authenticateAppPassword(
        pin: String,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.authenticate(pin)
                }
                checkIdentity()
            } catch (e: Exception) {
                onError("Incorrect password")
            }
        }
    }

    fun setError(message: String) {
        _uiState.value = UiState.Error(message)
    }

    fun sync() {
        viewModelScope.launch {
            if (!repository.hasIdentity()) {
                _syncState.value = SyncState.Idle
                return@launch
            }
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
                    if (result.updatedContactNames.isNotEmpty()) {
                        if (result.updatedContactNames.size == 1) {
                            localizationManager.t(
                                "sync.updated_single",
                                mapOf("name" to result.updatedContactNames.first()),
                            )
                        } else {
                            localizationManager.t(
                                "sync.updated_contacts",
                                mapOf("names" to result.updatedContactNames.joinToString(", ")),
                            )
                        }
                    } else if (result.contactsAdded > 0u || result.cardsUpdated > 0u) {
                        localizationManager.t(
                            "sync.message_format",
                            mapOf(
                                "cards_updated" to result.cardsUpdated.toString(),
                                "updates_sent" to result.contactsAdded.toString(),
                            ),
                        )
                    } else {
                        localizationManager.t("sync.no_changes")
                    }
                showMessage(msg)
            } catch (e: MobileException.RateLimited) {
                _syncState.value = SyncState.RateLimited(e.retryAfterSecs.toLong())
                showMessage("Please wait ${e.retryAfterSecs}s before syncing again")
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

    // --- NFC exchange ---

    /**
     * Create an NFC initiator (reader) handshake session.
     *
     * Called from [NfcExchangeScreen] before enabling reader mode.
     * Throws if no identity is available.
     */
    fun createNfcInitiator(): uniffi.vauchi_platform.MobileNfcHandshake = repository.createNfcInitiator()

    /**
     * Create an NFC responder (HCE) handshake session.
     *
     * Called from [NfcExchangeScreen] to pre-arm [VauchiHceService].
     * Throws if no identity is available.
     */
    fun createNfcResponder(): uniffi.vauchi_platform.MobileNfcHandshake = repository.createNfcResponder()

    // --- BLE exchange ---

    /**
     * Generate a QR-bootstrapped BLE exchange session.
     *
     * Returns [ExchangeSessionData] containing the session and the QR payload.
     * The caller MUST hold onto the session and pass it to [finalizeBleExchange].
     * Throws if no identity is available.
     */
    fun generateBleExchangeSession(): ExchangeSessionData = repository.generateExchangeQrWithSession()

    /**
     * Finalize a completed BLE exchange: save the received contact to storage.
     *
     * Returns the exchange result on success, null on failure.
     */
    fun finalizeBleExchange(session: uniffi.vauchi_platform.MobileExchangeSession): uniffi.vauchi_platform.MobileExchangeResult? =
        try {
            val result = repository.finalizeExchange(session)
            Log.i("Vauchi", "BLE exchange: contact finalized")
            result
        } catch (e: Exception) {
            Log.e("Vauchi", "BLE exchange: finalization failed: ${e.javaClass.simpleName}")
            null
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

    suspend fun listHiddenContacts(): List<MobileContact> =
        withContext(Dispatchers.IO) {
            repository.listHiddenContacts()
        }

    suspend fun hideContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.hideContact(id)
        }
    }

    suspend fun unhideContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.unhideContact(id)
        }
    }

    suspend fun archiveContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.archiveContact(id)
        }
    }

    suspend fun unarchiveContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.unarchiveContact(id)
        }
    }

    suspend fun softDeleteImportedContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.softDeleteImportedContact(id)
        }
    }

    suspend fun undoDeleteImportedContact(id: String) {
        withContext(Dispatchers.IO) {
            repository.undoDeleteImportedContact(id)
        }
    }

    /**
     * Returns the footer-button action id (`"delete_contact"` or
     * `"archive_contact"`) for the given contact. Views dispatch on
     * the returned id so they never branch on the imported-vs-exchanged
     * distinction in the view layer (§1A pure-renderer rule).
     */
    suspend fun contactDetailFooterActionId(contactId: String): String =
        withContext(Dispatchers.IO) {
            repository.contactDetailFooterActionId(contactId)
        }

    /**
     * G4 (ADR-021/043): typed contact-detail view-state.
     */
    suspend fun contactDetailViewState(contactId: String): uniffi.vauchi_platform.MobileContactDetailViewState =
        withContext(Dispatchers.IO) {
            repository.contactDetailViewState(contactId)
        }

    suspend fun listArchivedContacts(): List<MobileContact> =
        withContext(Dispatchers.IO) {
            repository.listArchivedContacts()
        }

    fun importContactsFromVcf(data: ByteArray) {
        viewModelScope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        repository.importContactsFromVcf(data)
                    }
                loadUserData()
                val msg = "${result.imported} contact(s) imported"
                val extra = if (result.skipped > 0u) ", ${result.skipped} skipped" else ""
                showMessage(msg + extra)
            } catch (e: Exception) {
                showMessage("Import failed: ${e.message}")
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

    // Contact Notes & Proposal Trust

    suspend fun setContactNote(
        contactId: String,
        note: String,
    ) {
        withContext(Dispatchers.IO) {
            repository.setContactNote(contactId, note)
        }
    }

    suspend fun getContactNote(contactId: String): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.getContactNote(contactId)
            }
        } catch (e: Exception) {
            null
        }

    suspend fun setContactFieldNote(
        contactId: String,
        fieldId: String,
        note: String,
    ) {
        withContext(Dispatchers.IO) {
            repository.setContactFieldNote(contactId, fieldId, note)
        }
    }

    suspend fun getContactFieldNotes(contactId: String): List<uniffi.vauchi_platform.MobileFieldNote> =
        try {
            withContext(Dispatchers.IO) {
                repository.getContactFieldNotes(contactId)
            }
        } catch (e: Exception) {
            emptyList()
        }

    suspend fun deleteContactFieldNote(
        contactId: String,
        fieldId: String,
    ) {
        withContext(Dispatchers.IO) {
            repository.deleteContactFieldNote(contactId, fieldId)
        }
    }

    suspend fun setProposalTrusted(
        contactId: String,
        trusted: Boolean,
    ): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.setProposalTrusted(contactId, trusted)
            }
            true
        } catch (e: Exception) {
            showMessage("Failed to update proposal trust: ${e.message}")
            false
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

    suspend fun exportFullBackup(password: String): String? =
        try {
            withContext(Dispatchers.IO) {
                repository.exportFullBackup(password)
            }
        } catch (e: Exception) {
            null
        }

    suspend fun importFullBackup(
        backupData: String,
        password: String,
    ): Boolean =
        try {
            withContext(Dispatchers.IO) {
                repository.importFullBackup(backupData, password)
            }
            loadUserData()
            true
        } catch (e: Exception) {
            false
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
    fun tryTriggerAhaMoment(momentType: uniffi.vauchi_platform.MobileAhaMomentType) {
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
        momentType: uniffi.vauchi_platform.MobileAhaMomentType,
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

    fun hasSeenAhaMoment(momentType: uniffi.vauchi_platform.MobileAhaMomentType): Boolean =
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

    // App password setup (post-onboarding prerequisite for duress PIN)
    private val _isPasswordEnabled = MutableStateFlow(false)
    val isPasswordEnabled: StateFlow<Boolean> = _isPasswordEnabled.asStateFlow()

    fun loadPasswordState() {
        _isPasswordEnabled.value =
            try {
                repository.isPasswordEnabled()
            } catch (_: Exception) {
                false
            }
    }

    fun setupAppPassword(password: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.setupAppPassword(password) }
                _isPasswordEnabled.value = true
                showMessage("App password set")
            } catch (e: Exception) {
                showMessage("Failed to set app password: ${e.message}")
            }
        }
    }

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

    // Recovery operations
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
     * Called automatically after a successful exchange.
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

    // MARK: - Device Linking Protocol

    sealed class DeviceLinkState {
        object Idle : DeviceLinkState()

        object GeneratingQR : DeviceLinkState()

        data class WaitingForRequest(
            val qrData: String,
            val expiresAt: ULong,
        ) : DeviceLinkState()

        object Expired : DeviceLinkState()

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

    private var currentSession: MobileDeviceLinkSession? = null

    /**
     * Listener bridge — forwards core's cycle-thread events onto the
     * UI state flow. Holds a weak-ish reference (cancel resets the session,
     * which detaches the listener slot) so the cycle thread can finish its
     * `on_session_ended` emit without leaking the ViewModel.
     */
    private inner class DeviceLinkSessionBridge : DeviceLinkSessionListener {
        override fun onQrReady(
            qrData: String,
            expiresAtUnix: ULong,
        ) {
            _deviceLinkState.value = DeviceLinkState.WaitingForRequest(qrData, expiresAtUnix)
        }

        override fun onConfirmationRequired(
            deviceName: String,
            confirmationCode: String,
            identityFingerprint: String,
            proximityChallenge: ByteArray,
        ) {
            _deviceLinkState.value =
                DeviceLinkState.ConfirmingDevice(
                    deviceName = deviceName,
                    confirmationCode = confirmationCode,
                    challenge = proximityChallenge,
                )
        }

        override fun onRequestSent(confirmationCode: String) {
            // Phase 1 responder-only — never fires from initiator cycle
        }

        override fun onCompleted(
            deviceName: String,
            deviceIndex: UInt,
        ) {
            _deviceLinkState.value = DeviceLinkState.Success
        }

        override fun onFailed(reason: String) {
            _deviceLinkState.value =
                if (reason == "qr_expired") DeviceLinkState.Expired else DeviceLinkState.Failed(reason)
        }

        override fun onSessionEnded() {
            // Final emit — idempotent reset if neither success/failed/expired fired
            when (_deviceLinkState.value) {
                is DeviceLinkState.Success,
                is DeviceLinkState.Failed,
                is DeviceLinkState.Expired,
                -> { /* terminal — leave as-is */ }

                else -> {
                    _deviceLinkState.value = DeviceLinkState.Idle
                }
            }
        }
    }

    /**
     * Start the device link protocol as initiator.
     *
     * Core's cycle thread owns QR generation, relay listening, and protocol
     * transitions. This method just primes the session; the listener bridge
     * receives all subsequent state changes asynchronously.
     */
    suspend fun startDeviceLinkInitiator() {
        _deviceLinkState.value = DeviceLinkState.GeneratingQR
        try {
            val session =
                withContext(Dispatchers.IO) {
                    repository.createDeviceLinkSessionInitiator()
                }
            currentSession = session
            session.setListener(DeviceLinkSessionBridge())
            session.start()
            // Listener callbacks will drive the next state transition
        } catch (e: Exception) {
            _deviceLinkState.value = DeviceLinkState.Failed(e.message ?: "Failed to start device link")
        }
    }

    /**
     * Cancel the device link protocol.
     */
    fun cancelDeviceLink() {
        currentSession?.let { runCatching { it.cancel() } }
        _deviceLinkState.value = DeviceLinkState.Idle
        currentSession = null
    }

    /**
     * Transition to expired state when the QR code times out.
     *
     * Retained for backward compatibility with the view layer; new code should
     * rely on the listener's `on_failed("qr_expired")` callback instead. Core
     * owns the expiry clock now (no frontend timer needed).
     */
    fun setDeviceLinkExpired() {
        _deviceLinkState.value = DeviceLinkState.Expired
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
    fun scheduleIdentityDeletion() {
        viewModelScope.launch {
            try {
                val info =
                    withContext(Dispatchers.IO) {
                        repository.scheduleIdentityDeletion()
                    }
                _deletionState.value = info
            } catch (e: Exception) {
                showMessage("Schedule failed: ${e.message}")
            }
        }
    }

    /** Cancel a scheduled account deletion. */
    fun cancelIdentityDeletion() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.cancelIdentityDeletion()
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

    /**
     * Handle app backgrounded event (C1 auto-lock).
     */
    fun handleAppBackgrounded() {
        val screenJson = repository.handleAppBackgrounded()
        if (screenJson != null) {
            // Core navigated to Lock screen — require re-authentication
            _uiState.value = UiState.AuthRequired
        }
    }

    /**
     * Poll for and return OS notifications (E).
     */
    fun pollNotifications() = repository.pollNotifications()
}

private data class Tuple4<A, B, C, D>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
)
