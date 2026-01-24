package com.vauchi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vauchi.data.VauchiRepository
import com.vauchi.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_mobile.MobileContact
import uniffi.vauchi_mobile.MobileContactCard
import uniffi.vauchi_mobile.MobileDemoContact
import uniffi.vauchi_mobile.MobileDemoContactState
import uniffi.vauchi_mobile.MobileExchangeData
import uniffi.vauchi_mobile.MobileExchangeResult
import uniffi.vauchi_mobile.MobileFieldType
import uniffi.vauchi_mobile.MobileRecoveryClaim
import uniffi.vauchi_mobile.MobileRecoveryProgress
import uniffi.vauchi_mobile.MobileRecoveryVoucher
import uniffi.vauchi_mobile.MobileSocialNetwork
import uniffi.vauchi_mobile.MobileSyncResult
import uniffi.vauchi_mobile.MobileProximityVerifier
import com.vauchi.proximity.AudioProximityService
import java.time.Instant

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val result: MobileSyncResult) : SyncState()
    data class Error(val message: String) : SyncState()
}

sealed class UiState {
    object Loading : UiState()
    object Setup : UiState()
    object Onboarding : UiState()
    data class Ready(
        val displayName: String,
        val publicId: String,
        val card: MobileContactCard,
        val contactCount: UInt
    ) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
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

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Network connectivity state
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
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

    // Accessibility settings
    private val _reduceMotion = MutableStateFlow(false)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val _highContrast = MutableStateFlow(false)
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    private val _largeTouchTargets = MutableStateFlow(false)
    val largeTouchTargets: StateFlow<Boolean> = _largeTouchTargets.asStateFlow()

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
        loadAccessibilitySettings()
    }

    private fun loadAccessibilitySettings() {
        _reduceMotion.value = repository.getReduceMotion()
        _highContrast.value = repository.getHighContrast()
        _largeTouchTargets.value = repository.getLargeTouchTargets()
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
                val hasIdentity = withContext(Dispatchers.IO) {
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
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun completeOnboarding(displayName: String, phone: String?, email: String?) {
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
            val (displayName, publicId, card, contactCount) = withContext(Dispatchers.IO) {
                Tuple4(
                    repository.getDisplayName(),
                    repository.getPublicId(),
                    repository.getOwnCard(),
                    repository.contactCount()
                )
            }
            _uiState.value = UiState.Ready(displayName, publicId, card, contactCount)
            // Load demo contact state
            loadDemoContact()
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
                val result = withContext(Dispatchers.IO) {
                    repository.sync()
                }
                _syncState.value = SyncState.Success(result)
                _lastSyncTime.value = Instant.now()
                loadUserData()
                val msg = buildString {
                    append("Sync complete")
                    if (result.contactsAdded > 0u) append(" - ${result.contactsAdded} new contacts")
                    if (result.cardsUpdated > 0u) append(" - ${result.cardsUpdated} cards updated")
                }
                showMessage(msg)
            } catch (e: Exception) {
                val errorMsg = if (!networkMonitor.isCurrentlyConnected()) {
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

    fun addField(fieldType: MobileFieldType, label: String, value: String) {
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

    fun updateField(label: String, newValue: String) {
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

    suspend fun generateExchangeQr(): MobileExchangeData? {
        return try {
            withContext(Dispatchers.IO) {
                repository.generateExchangeQr()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun completeExchange(qrData: String): MobileExchangeResult? {
        return try {
            val result = withContext(Dispatchers.IO) {
                repository.completeExchange(qrData)
            }
            loadUserData()
            // Auto-remove demo contact after first real exchange
            if (result != null && result.success) {
                autoRemoveDemoContact()
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    suspend fun listContacts(): List<MobileContact> {
        return try {
            withContext(Dispatchers.IO) {
                repository.listContacts()
            }
        } catch (e: Exception) {
            emptyList()
        }
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

    suspend fun getContact(id: String): MobileContact? {
        return try {
            withContext(Dispatchers.IO) {
                repository.getContact(id)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun verifyContact(id: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                repository.verifyContact(id)
            }
            showMessage("Contact verified successfully")
            true
        } catch (e: Exception) {
            showMessage("Failed to verify contact: ${e.message}")
            false
        }
    }

    suspend fun getOwnPublicKey(): String? {
        return try {
            withContext(Dispatchers.IO) {
                repository.getPublicKey()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getOwnCard(): MobileContactCard? {
        return try {
            withContext(Dispatchers.IO) {
                repository.getOwnCard()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setFieldVisibility(contactId: String, fieldLabel: String, visible: Boolean) {
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

    suspend fun isFieldVisibleToContact(contactId: String, fieldLabel: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                repository.isFieldVisibleToContact(contactId, fieldLabel)
            }
        } catch (e: Exception) {
            true // Default to visible on error
        }
    }

    suspend fun exportBackup(password: String): String? {
        return try {
            withContext(Dispatchers.IO) {
                repository.exportBackup(password)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun importBackup(backupData: String, password: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                repository.importBackup(backupData, password)
            }
            loadUserData()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun checkPasswordStrength(password: String): PasswordStrengthResult {
        return try {
            val check = repository.checkPasswordStrength(password)
            PasswordStrengthResult(
                level = when (check.strength) {
                    uniffi.vauchi_mobile.MobilePasswordStrength.TOO_WEAK -> PasswordStrengthLevel.TooWeak
                    uniffi.vauchi_mobile.MobilePasswordStrength.FAIR -> PasswordStrengthLevel.Fair
                    uniffi.vauchi_mobile.MobilePasswordStrength.STRONG -> PasswordStrengthLevel.Strong
                    uniffi.vauchi_mobile.MobilePasswordStrength.VERY_STRONG -> PasswordStrengthLevel.VeryStrong
                },
                description = check.description,
                feedback = check.feedback,
                isAcceptable = check.isAcceptable
            )
        } catch (e: Exception) {
            PasswordStrengthResult()
        }
    }

    // Social network operations
    fun listSocialNetworks(): List<MobileSocialNetwork> {
        return try {
            repository.listSocialNetworks()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun searchSocialNetworks(query: String): List<MobileSocialNetwork> {
        return try {
            repository.searchSocialNetworks(query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getProfileUrl(networkId: String, username: String): String? {
        return try {
            repository.getProfileUrl(networkId, username)
        } catch (e: Exception) {
            null
        }
    }

    // Recovery operations
    suspend fun createRecoveryClaim(oldPkHex: String): MobileRecoveryClaim? {
        return try {
            withContext(Dispatchers.IO) {
                repository.createRecoveryClaim(oldPkHex)
            }
        } catch (e: Exception) {
            showMessage("Failed to create claim: ${e.message}")
            null
        }
    }

    suspend fun parseRecoveryClaim(claimB64: String): MobileRecoveryClaim? {
        return try {
            withContext(Dispatchers.IO) {
                repository.parseRecoveryClaim(claimB64)
            }
        } catch (e: Exception) {
            showMessage("Invalid claim data: ${e.message}")
            null
        }
    }

    suspend fun createRecoveryVoucher(claimB64: String): MobileRecoveryVoucher? {
        return try {
            withContext(Dispatchers.IO) {
                repository.createRecoveryVoucher(claimB64)
            }
        } catch (e: Exception) {
            showMessage("Failed to create voucher: ${e.message}")
            null
        }
    }

    suspend fun addRecoveryVoucher(voucherB64: String): MobileRecoveryProgress? {
        return try {
            withContext(Dispatchers.IO) {
                repository.addRecoveryVoucher(voucherB64)
            }
        } catch (e: Exception) {
            showMessage("Failed to add voucher: ${e.message}")
            null
        }
    }

    suspend fun getRecoveryStatus(): MobileRecoveryProgress? {
        return try {
            withContext(Dispatchers.IO) {
                repository.getRecoveryStatus()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getRecoveryProof(): String? {
        return try {
            withContext(Dispatchers.IO) {
                repository.getRecoveryProof()
            }
        } catch (e: Exception) {
            null
        }
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
                val demo = withContext(Dispatchers.IO) {
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
                val demo = withContext(Dispatchers.IO) {
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
                val removed = withContext(Dispatchers.IO) {
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
                val demo = withContext(Dispatchers.IO) {
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
                val demo = withContext(Dispatchers.IO) {
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
    fun isDemoUpdateAvailable(): Boolean {
        return try {
            repository.isDemoUpdateAvailable()
        } catch (e: Exception) {
            false
        }
    }
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
