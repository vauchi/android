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
import uniffi.vauchi_platform.PlatformAppEngine

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

    object Ready : UiState()

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

    private val networkMonitor = NetworkMonitor(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val isOnline: StateFlow<Boolean> =
        networkMonitor.isOnline
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Snackbar message channel for user feedback
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

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
                        _uiState.value = UiState.Ready
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
                    true -> _uiState.value = UiState.Ready
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
     * frontend hook refreshes UI state without creating another identity.
     *
     * Calling `repository.createIdentity` here would double-create
     * (or fail, depending on storage semantics) since PAE already
     * wrote the identity inside core.
     */
    fun onCoreOnboardingComplete() {
        _uiState.value = UiState.Ready
    }

    fun retryStartup() {
        checkIdentity()
    }

    fun getRelayUrl(): String = repository.getRelayUrl()

    fun setRelayUrl(url: String) {
        repository.setRelayUrl(url)
        showMessage("Relay URL updated (restart app to apply)")
    }

    /**
     * Poll for and return OS notifications (E).
     */
    fun pollNotifications(): List<NotificationPresentation> = repository.pollNotifications()
}

/** Core's `SetAuthenticationRequirement` value that asks for the app password. */
