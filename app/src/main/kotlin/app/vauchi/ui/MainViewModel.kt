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
import app.vauchi.data.KeyInvalidatedRecoveryRequired
import app.vauchi.data.VauchiRepository
import app.vauchi.ui.coreui.ActionResult
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
import kotlinx.serialization.json.Json
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileConsentType
import uniffi.vauchi_platform.MobileContact
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileGdprExport
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

    /**
     * The KeyStore master key was invalidated and the local encrypted state
     * has been wiped. The user must pick a recovery path.
     *
     * @property hadData true when the user previously had a working
     *   identity whose data was lost; false on a true fresh-install path
     *   that hit an inherited invalidated alias (route silently to
     *   onboarding via [MainViewModel.onRecoveryStartFresh]).
     */
    data class KeyInvalidatedRecovery(
        val hadData: Boolean,
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

    fun setReduceMotion(enabled: Boolean) {
        repository.setReduceMotion(enabled)
    }

    fun setHighContrast(enabled: Boolean) {
        repository.setHighContrast(enabled)
    }

    fun setLargeTouchTargets(enabled: Boolean) {
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
            } catch (e: KeyInvalidatedRecoveryRequired) {
                android.util.Log.e("Vauchi", "checkIdentity: key invalidated, hadData=${e.hadData}", e)
                if (e.hadData) {
                    _uiState.value = UiState.KeyInvalidatedRecovery(hadData = true)
                } else {
                    // True fresh install — wipe already done, route silently
                    _uiState.value = UiState.Onboarding
                }
            } catch (e: Exception) {
                android.util.Log.e("Vauchi", "checkIdentity: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Called when core-driven onboarding completes — slice 32c moved
     * the identity creation + groups + fields persistence into
     * `AppEngine::handle_completion` (vauchi-app routing.rs), so this
     * frontend hook no longer touches identity. It just flips the
     * local onboarding-completed preference and refreshes UI state
     * so MainActivity transitions from `UiState.Onboarding` to
     * `UiState.Ready`.
     *
     * Calling `repository.createIdentity` here would double-create
     * (or fail, depending on storage semantics) since PAE already
     * wrote the identity inside core.
     */
    fun onCoreOnboardingComplete() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
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
        } catch (e: DeviceNotSecureException) {
            _uiState.value =
                UiState.Error(
                    e.message ?: "A secure lock screen is required to use Vauchi.",
                )
        } catch (e: AuthenticationRequiredException) {
            android.util.Log.e("Vauchi", "loadUserData: auth required", e)
            _uiState.value = UiState.AuthRequired
        } catch (e: KeyInvalidatedRecoveryRequired) {
            android.util.Log.e("Vauchi", "loadUserData: key invalidated, hadData=${e.hadData}", e)
            _uiState.value =
                if (e.hadData) UiState.KeyInvalidatedRecovery(hadData = true) else UiState.Onboarding
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

    /**
     * User chose "Set up new identity" on the key-invalidated recovery
     * screen. Storage state has already been wiped at the moment the
     * recovery state was entered; re-run identity check so the next
     * storage init (now clean) routes to onboarding.
     */
    fun onRecoveryStartFresh() {
        _uiState.value = UiState.Loading
        checkIdentity()
    }

    /** Re-run full initialization (identity check + load). Use after biometric auth. */
    private val biometricJson = Json { ignoreUnknownKeys = true }

    fun retryInit() {
        viewModelScope.launch {
            // Core owns the post-biometric duress decision and the
            // 300 ms constant-time floor that hides whether duress is
            // configured (audit
            // `2026-04-28-lifecycle-session-residue-umbrella` P2-B).
            // ADR-031: biometric success is reported as a hardware
            // event; core consults its duress state (sleeping in Rust
            // for ≥ BIOMETRIC_UNLOCK_MIN_DURATION) and returns the
            // outcome as ActionResult.BiometricUnlockOutcome. Dispatch
            // off the main thread.
            val outcome =
                try {
                    withContext(Dispatchers.IO) {
                        appEngine.handleHardwareEvent(MobileEvent.BiometricUnlockSucceeded)
                    }?.let { resultJson ->
                        (
                            biometricJson.decodeFromString<ActionResult>(resultJson)
                                as? ActionResult.BiometricUnlockOutcome
                        )?.outcome
                    }
                } catch (_: Exception) {
                    null
                }

            when (outcome) {
                "PromptForDuressPin" -> {
                    _uiState.value = UiState.AppPasswordRequired
                }

                // "Unlocked", or null on a missing outcome / decode
                // failure: proceed to the normal identity-check path,
                // matching the prior behavior on a null biometric check.
                else -> {
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
                // Logging-rules.md format: `[<Module>] Failed: <error_type_only>`.
                // The exception class (e.g. `MobileException$Other`) is not PII;
                // captures enough to triage without surfacing message contents.
                // F2-MED-2 needed exactly this signal — the previous catch
                // swallowed the exception class entirely, leaving only the
                // user-facing toast as a diagnostic.
                Log.e("Vauchi", "[Sync] Failed: ${e.javaClass.simpleName}", e)
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

    suspend fun listArchivedContacts(): List<MobileContact> =
        withContext(Dispatchers.IO) {
            repository.listArchivedContacts()
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

    // Demo contact operations
    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     */
    fun initDemoContactIfNeeded() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.initDemoContactIfNeeded()
                }
            } catch (e: Exception) {
                // Silently fail - demo is optional
            }
        }
    }

    /**
     * Trigger a demo update
     */
    fun triggerDemoUpdate() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.triggerDemoUpdate()
                }
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
                onSuccess()
                showMessage("Label deleted")
            } catch (e: Exception) {
                onError(e.message ?: "Failed to delete label")
                showMessage("Failed to delete label: ${e.message}")
            }
        }
    }

    // MARK: - Device Management
    // Based on: features/device_management.feature

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
                withContext(Dispatchers.IO) {
                    repository.scheduleIdentityDeletion()
                }
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
            } catch (e: Exception) {
                showMessage("Cancel failed: ${e.message}")
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
            } catch (e: Exception) {
                showMessage("Revoke failed: ${e.message}")
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
