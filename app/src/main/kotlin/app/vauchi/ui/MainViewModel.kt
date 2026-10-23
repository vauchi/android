// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.app.Application
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vauchi.data.DeviceNotSecureException
import app.vauchi.data.VauchiRepository
import app.vauchi.util.LocalizationManager
import app.vauchi.util.NetworkMonitor
import app.vauchi.util.NotificationPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.MobileAhaMomentType
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileSyncResult
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

    /**
     * Core is open and presents the start itself, but cannot yet say whether
     * there is an identity: it started locked and shows its own unlock or
     * recovery screen (ADR-043 Amendment 7). Rendered from Core's commands;
     * the next presentation transition settles it into [Onboarding] or [Ready].
     */
    object Starting : UiState()

    data class Ready(
        val displayName: String,
        val publicId: String,
        val card: MobileContactCard,
        val contactCount: UInt,
    ) : UiState()

    data class Error(
        val kind: StartupErrorKind,
        val detail: String? = null,
    ) : UiState()
}

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: VauchiRepository by lazy {
        VauchiRepository.getInstance(application)
    }

    val appEngine: PlatformAppEngine
        get() = repository.appEngine

    private val localizationManager = LocalizationManager.getInstance(application)
    private val networkMonitor = NetworkMonitor(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val isOnline: StateFlow<Boolean> =
        networkMonitor.isOnline
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Snackbar message channel for user feedback
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Instant?>(null)
    val lastSyncTime: StateFlow<Instant?> = _lastSyncTime.asStateFlow()
    private var presentationReconciliationInFlight = false

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * Reconcile the native startup gate after a Core presentation transition.
     * No surface or action identifier is interpreted here: identity existence
     * is the boundary between startup onboarding and the normal app.
     */
    fun reconcilePresentationState() {
        val startState = _uiState.value
        if ((startState !is UiState.Onboarding && startState !is UiState.Starting) || presentationReconciliationInFlight) {
            return
        }
        presentationReconciliationInFlight = true
        viewModelScope.launch {
            try {
                val identityExists = withContext(Dispatchers.IO) { identityOnceOpen() }
                when {
                    _uiState.value !== startState -> {
                        Unit
                    }

                    startState is UiState.Starting && identityExists == true -> {
                        loadUserData()
                    }

                    startState is UiState.Starting && identityExists == false -> {
                        _uiState.value = UiState.Onboarding
                    }

                    startState is UiState.Onboarding && identityExists == true -> {
                        onCoreOnboardingComplete()
                    }
                }
            } finally {
                presentationReconciliationInFlight = false
            }
        }
    }

    /**
     * Whether there is an identity, or `null` while Core cannot say yet. Core
     * starts locked rather than failing to open (ADR-043 Amendment 7), and it
     * classifies why (ADR-045), so any failure to answer leaves the start to
     * Core's own screen. Failing to open the engine at all still throws.
     */
    private fun identityOnceOpen(): Boolean? {
        val engine = repository.appEngine
        return runCatching { engine.hasIdentity() }.getOrNull()
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
     * Forward `NetworkMonitor` reachability into core so presentation state
     * reflects offline status (audit
     * `2026-04-28-lifecycle-session-residue-umbrella` P2-D).
     * The frontend keeps `isOnline` for native startup feedback; the
     * presentation decision lives in core.
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
                    _uiState.value = UiState.Error(StartupErrorKind.DeviceNotSecure)
                    return@launch
                }

                when (withContext(Dispatchers.IO) { identityOnceOpen() }) {
                    true -> loadUserData()
                    false -> _uiState.value = UiState.Onboarding
                    null -> _uiState.value = UiState.Starting
                }
            } catch (e: DeviceNotSecureException) {
                _uiState.value = UiState.Error(StartupErrorKind.DeviceNotSecure)
            } catch (e: Exception) {
                android.util.Log.e("Vauchi", "checkIdentity: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.value = UiState.Error(StartupErrorKind.Other, e.message)
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
                _uiState.value = UiState.Error(StartupErrorKind.Other, e.message)
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
            _uiState.value = UiState.Error(StartupErrorKind.DeviceNotSecure)
        } catch (e: Exception) {
            android.util.Log.e("Vauchi", "loadUserData: ${e.javaClass.simpleName}: ${e.message}", e)
            _uiState.value = UiState.Error(StartupErrorKind.Other, e.message)
        }
    }

    fun retryStartup() {
        checkIdentity()
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

                // Surface the first-update-received aha moment when this sync
                // actually brought in changes.
                // TODO(HUMBLE): D, P1. Frontend decides FIRST_UPDATE_RECEIVED aha
                // moment from sync result counts. Fix: core returns moments
                // directly. (see _private problem record
                // 2026-07-06-mobile-domain-shell-violations)
                if (result.hasChanges) {
                    repository.tryTriggerAhaMoment(MobileAhaMomentType.FIRST_UPDATE_RECEIVED)?.let { moment ->
                        showMessage(moment.message)
                    }
                }

                // TODO(HUMBLE): T/W, P1. Assembles sync result copy from domain
                // counts/names (updatedContactNames, contactsAdded, cardsUpdated).
                // Fix: core returns localized summary or ShowToast. (see _private
                // problem record 2026-07-06-mobile-domain-shell-violations)
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
                showMessage(
                    localizationManager.t(
                        "rate_limit.retry_in",
                        mapOf("seconds" to e.retryAfterSecs.toString()),
                    ),
                )
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
                        localizationManager.t("sync.offline_banner")
                    } else {
                        e.message ?: localizationManager.t("sync.error_failed")
                    }
                _syncState.value = SyncState.Error(errorMsg)
                showMessage("${localizationManager.t("sync.error_failed")}: $errorMsg")
            }
        }
    }

    fun getRelayUrl(): String = repository.getRelayUrl()

    fun setRelayUrl(url: String) {
        repository.setRelayUrl(url)
        showMessage("Relay URL updated (restart app to apply)")
    }

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
     * Poll for and return OS notifications (E).
     */
    fun pollNotifications(): List<NotificationPresentation> = repository.pollNotifications()
}

private data class Tuple4<A, B, C, D>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
)

/** Core's `SetAuthenticationRequirement` value that asks for the app password. */
