// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.MobileLocale
import uniffi.vauchi_platform.MobileTabInfo
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * ViewModel that bridges [PlatformAppEngine] to Compose UI for core-driven screens.
 *
 * This is the Android equivalent of iOS/macOS AppViewModel. A single instance
 * is shared across all core-driven tabs — one engine, one DB connection.
 *
 * Core describes what to render via [ScreenModel]. User interactions flow back
 * as [UserAction] JSON. Core returns [ActionResult] directing the UI.
 */
class CoreAppViewModel(
    private val appEngine: PlatformAppEngine,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _screen = MutableStateFlow<ScreenModel?>(null)
    val screen: StateFlow<ScreenModel?> = _screen.asStateFlow()

    private val _availableScreens = MutableStateFlow<List<String>>(emptyList())
    val availableScreens: StateFlow<List<String>> = _availableScreens.asStateFlow()

    /**
     * Top-level tabs as core describes them — `id` (snake_case
     * `screen_id`), `label` (locale-resolved), `icon` (SF Symbol name
     * Android maps to a Material Icon), `badge_count`. Empty before
     * identity exists or before the first [loadTabs] call. Driven by
     * `PlatformAppEngine.tabInfo(locale)`; reload via [loadTabs] when
     * identity transitions or the user changes locale.
     */
    private val _tabs = MutableStateFlow<List<MobileTabInfo>>(emptyList())
    val tabs: StateFlow<List<MobileTabInfo>> = _tabs.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _toastUndoActionId = MutableStateFlow<String?>(null)
    val toastUndoActionId: StateFlow<String?> = _toastUndoActionId.asStateFlow()

    private val _alertMessage = MutableStateFlow<Pair<String, String>?>(null)
    val alertMessage: StateFlow<Pair<String, String>?> = _alertMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openUrlEvent = MutableStateFlow<String?>(null)
    val openUrlEvent: StateFlow<String?> = _openUrlEvent.asStateFlow()

    fun consumeOpenUrlEvent() {
        _openUrlEvent.value = null
    }

    private val _backupExportData = MutableStateFlow<String?>(null)
    val backupExportData: StateFlow<String?> = _backupExportData.asStateFlow()

    fun consumeBackupExportData() {
        _backupExportData.value = null
    }

    /**
     * Image picking events emitted by core via ExchangeCommands.
     * Values: "library", "camera", null (consumed).
     */
    private val _imagePickEvent = MutableStateFlow<String?>(null)
    val imagePickEvent: StateFlow<String?> = _imagePickEvent.asStateFlow()

    fun consumeImagePickEvent() {
        _imagePickEvent.value = null
    }

    /**
     * Screen brightness requests emitted by core's
     * `Command::SetScreenBrightness` (Phase 2b screen-presentation
     * lifecycle). [BrightnessRequest.Set] dims/brightens to the
     * requested level (0.0–1.0); [BrightnessRequest.Restore] restores
     * the platform default. The Activity-side collector snapshots the
     * prior `Window.attributes.screenBrightness` on the first `Set`
     * so the next `Restore` correctly reverts.
     *
     * `null` means "no pending request"; consumed by
     * [consumeBrightnessRequest] after the collector applies it.
     */
    private val _brightnessRequest = MutableStateFlow<BrightnessRequest?>(null)
    val brightnessRequest: StateFlow<BrightnessRequest?> = _brightnessRequest.asStateFlow()

    fun consumeBrightnessRequest() {
        _brightnessRequest.value = null
    }

    /**
     * Idle-timer / keep-screen-on requests emitted by core's
     * `Command::SetIdleTimerDisabled`. `true` means "disable the idle
     * timer" (`FLAG_KEEP_SCREEN_ON`); `false` means restore default.
     * `null` means "no pending request".
     */
    private val _idleTimerDisabledRequest = MutableStateFlow<Boolean?>(null)
    val idleTimerDisabledRequest: StateFlow<Boolean?> = _idleTimerDisabledRequest.asStateFlow()

    fun consumeIdleTimerDisabledRequest() {
        _idleTimerDisabledRequest.value = null
    }

    /**
     * Orientation lock requests emitted by core's
     * `Command::SetOrientationLock` (Phase 2c screen-presentation
     * lifecycle). [OrientationLockRequest.Lock] clamps the Activity's
     * `requestedOrientation` to the requested mask;
     * [OrientationLockRequest.Restore] returns to the platform default.
     * `null` means "no pending request".
     */
    private val _orientationLockRequest = MutableStateFlow<OrientationLockRequest?>(null)
    val orientationLockRequest: StateFlow<OrientationLockRequest?> = _orientationLockRequest.asStateFlow()

    fun consumeOrientationLockRequest() {
        _orientationLockRequest.value = null
    }

    /**
     * Active camera-selector preference for `Component::QrCode`'s
     * scan mode. Flips when core's `MultiStageExchangeEngine` emits
     * `Command::SwitchCamera { use_front }` in response to the
     * `switch_camera` action — the QR scanner Composable reads this
     * StateFlow via [LocalUseFrontCamera] and re-binds CameraX with
     * `DEFAULT_FRONT_CAMERA` / `DEFAULT_BACK_CAMERA` accordingly.
     *
     * Default `false` (back camera). Persists for the lifetime of the
     * ViewModel (one camera-orientation choice across the screen
     * navigations within a single core session).
     */
    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    /**
     * Called by the Activity/Composable when the user picks or captures an image.
     * Sends the image bytes back to core as an ImageReceived hardware event.
     */
    fun handleImageReceived(imageBytes: ByteArray) {
        sendHardwareEvent(MobileEvent.ImageReceived(data = imageBytes))
    }

    /**
     * Called when the user cancels the image picker.
     */
    fun handleImagePickCancelled() {
        sendHardwareEvent(MobileEvent.ImagePickCancelled)
    }

    private fun sendHardwareEvent(event: MobileEvent) {
        viewModelScope.launch {
            try {
                val resultJson =
                    withContext(Dispatchers.IO) {
                        appEngine.handleHardwareEvent(event = event)
                    }
                if (resultJson != null) {
                    val result = json.decodeFromString<ActionResult>(resultJson)
                    applyResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send hardware event", e)
            }
        }
    }

    /**
     * Phase 2A/2B companion to iOS AppViewModel's event listener. Kept
     * as a property so its lifetime is bound to the ViewModel — if it
     * gets collected, UniFFI stops delivering callbacks.
     */
    private var eventListener: ScreenInvalidationListener? = null

    init {
        loadAvailableScreens()
        loadScreen()
        attachEventListener()
    }

    private fun attachEventListener() {
        val listener =
            ScreenInvalidationListener { screenIds ->
                // Core may fire this on the same thread that called
                // handleActionJson. Hopping back into the engine there
                // would deadlock the internal Mutex — bounce through
                // viewModelScope first.
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        for (id in screenIds) {
                            runCatching { appEngine.invalidateScreenJson("\"$id\"") }
                        }
                    }
                    loadScreen()
                }
            }
        try {
            appEngine.setEventListener(listener = listener)
            eventListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach event listener", e)
        }
    }

    /** Test-only accessor; see `ScreenInvalidationListenerTest`. */
    internal val hasEventListener: Boolean
        get() = eventListener != null

    fun loadAvailableScreens() {
        viewModelScope.launch {
            try {
                val screensJson =
                    withContext(Dispatchers.IO) {
                        appEngine.availableScreensJson()
                    }
                _availableScreens.value = json.decodeFromString(screensJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load available screens", e)
            }
        }
    }

    /**
     * Refresh [tabs] from `PlatformAppEngine.tabInfo(locale)`. Call on
     * startup, after identity creation (pre-identity returns just
     * Onboarding; post-identity returns the five mobile top-level
     * tabs), and whenever the active locale changes so labels stay in
     * sync. Errors are logged and leave the previous tabs in place.
     */
    fun loadTabs(locale: MobileLocale) {
        viewModelScope.launch {
            try {
                _tabs.value =
                    withContext(Dispatchers.IO) {
                        appEngine.tabInfo(locale = locale)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tabs", e)
            }
        }
    }

    fun loadScreen() {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.currentScreenJson()
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load screen", e)
                _error.value = "Failed to load screen: ${e.message}"
            }
        }
    }

    fun handleAction(action: UserAction) {
        viewModelScope.launch {
            try {
                val actionJson = json.encodeToString(UserAction.serializer(), action)
                val resultJson =
                    withContext(Dispatchers.IO) {
                        appEngine.handleActionJson(actionJson = actionJson)
                    }
                // Phase 2b: handleActionJson returns
                // `{"action_result": <ActionResult>, "commands": [<CommandDTO>]}`.
                // The lifecycle commands carry brightness / idle-timer
                // requests emitted by
                // `WorkflowEngine::screen_entered/screen_exited`.
                val envelope = json.decodeFromString<ActionResultEnvelope>(resultJson)
                applyResult(envelope.actionResult)
                if (envelope.commands.isNotEmpty()) {
                    handleExchangeCommands(envelope.commands)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle action", e)
                _error.value = "Action failed: ${e.message}"
            }
        }
    }

    /**
     * Dispatch an incoming `vauchi://exchange?...` deep link URI to core.
     *
     * On success core navigates to `AppScreen::DeepLinkConsent` and
     * `screen` updates to the consent ScreenModel — observers (the
     * native consent dialog) react via `screen.collectAsState()`.
     *
     * On parse failure, [onInvalid] is invoked with a human-readable
     * detail (UniFFI `MobileError::InvalidInput.detail`). The native
     * UI surfaces this via snackbar.
     */
    fun handleDeepLinkUri(
        uri: String,
        onInvalid: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.handleDeepLinkUri(uri = uri)
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
                loadAvailableScreens()
            } catch (e: Exception) {
                Log.e(TAG, "Deep link dispatch failed", e)
                onInvalid(e.message ?: "Unknown error")
            }
        }
    }

    fun navigateTo(screenName: String) {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.navigateToJson(screenJson = "\"$screenName\"")
                    }
                // Phase 2b envelope shape: `{"screen": ..., "commands": [...]}`.
                val envelope = json.decodeFromString<ScreenEnvelope>(screenJson)
                _screen.value = envelope.screen
                loadAvailableScreens()
                if (envelope.commands.isNotEmpty()) {
                    handleExchangeCommands(envelope.commands)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate to $screenName", e)
            }
        }
    }

    /**
     * Navigate to a parameterized AppScreen variant — `{"ContactDetail":
     * {"contact_id": "..."}}` and similar. Mirror of iOS
     * `AppViewModel.navigateToScreen(["ContactDetail": ["contact_id": …]])`.
     */
    fun navigateToScreenWithParam(
        screenName: String,
        paramKey: String,
        paramValue: String,
    ) {
        viewModelScope.launch {
            try {
                // Construct {"<screenName>": {"<paramKey>": "<paramValue>"}}
                val payload =
                    buildJsonObject {
                        put(
                            screenName,
                            buildJsonObject {
                                put(paramKey, JsonPrimitive(paramValue))
                            },
                        )
                    }
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.navigateToJson(screenJson = payload.toString())
                    }
                val envelope = json.decodeFromString<ScreenEnvelope>(screenJson)
                _screen.value = envelope.screen
                loadAvailableScreens()
                if (envelope.commands.isNotEmpty()) {
                    handleExchangeCommands(envelope.commands)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate to $screenName with $paramKey=$paramValue", e)
            }
        }
    }

    fun navigateBack() {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.navigateBackJson()
                    }
                val envelope = json.decodeFromString<ScreenEnvelope>(screenJson)
                _screen.value = envelope.screen
                if (envelope.commands.isNotEmpty()) {
                    handleExchangeCommands(envelope.commands)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate back", e)
            }
        }
    }

    fun invalidateAll() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appEngine.invalidateAll() }
                loadAvailableScreens()
                loadScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invalidate", e)
            }
        }
    }

    fun dismissToast() {
        _toastMessage.value = null
        _toastUndoActionId.value = null
    }

    /**
     * Surface a transient toast directly. Mirrors the
     * `ActionResult::ShowToast` path but lets non-action callers
     * (e.g. [LinkResponderSessionService] reporting a typed
     * `on_failed` reason) drop a message into the same Snackbar
     * pipeline. Shape mirrors iOS `AppViewModel.showToast(...)`.
     */
    fun showToast(
        message: String,
        undoActionId: String? = null,
    ) {
        _toastMessage.value = message
        _toastUndoActionId.value = undoActionId
    }

    /**
     * Internal accessor for the cached `PlatformAppEngine`. Used by
     * the `LinkResponderSessionService` Phase 2b wire-up to call
     * `currentLinkResponderSession()` without breaking encapsulation
     * of the rest of the engine surface.
     */
    internal fun platformAppEngine(): PlatformAppEngine = appEngine

    fun dismissAlert() {
        _alertMessage.value = null
    }

    private fun applyResult(result: ActionResult) {
        when (result) {
            is ActionResult.UpdateScreen -> {
                _screen.value = result.screen
            }

            is ActionResult.NavigateTo -> {
                _screen.value = result.screen
            }

            is ActionResult.ValidationError -> {
                // Core patches validation into screen components via AppEngine
                loadScreen()
            }

            is ActionResult.Complete, is ActionResult.WipeComplete -> {
                loadScreen()
            }

            is ActionResult.ShowToast -> {
                _toastMessage.value = result.message
                _toastUndoActionId.value = result.undoActionId
            }

            is ActionResult.ShowAlert -> {
                _alertMessage.value = Pair(result.title, result.message)
            }

            is ActionResult.OpenContact -> {
                navigateToScreenWithParam("ContactDetail", "contact_id", result.contactId)
            }

            is ActionResult.EditContact -> {
                navigateToScreenWithParam("ContactEdit", "contact_id", result.contactId)
            }

            is ActionResult.OpenEntryDetail -> {
                navigateToScreenWithParam("EntryDetail", "field_id", result.fieldId)
            }

            is ActionResult.OpenUrl -> {
                _openUrlEvent.value = result.url
            }

            is ActionResult.Commands -> {
                handleExchangeCommands(result.commands)
            }

            is ActionResult.ShowFormDialog -> {
                // Dialog presentation handled by NavigateTo — no separate action needed
            }

            is ActionResult.PreviewAs -> {
                // Card preview handled by NavigateTo — no separate action needed
            }

            is ActionResult.StartDeviceLink -> {
                // Handled by native Android flows
            }

            is ActionResult.BackupExportComplete -> {
                // Core executed the backup — surface the data for sharing.
                // The encrypted hex is in result.data; emit to UI for save/share.
                _backupExportData.value = result.data
                loadScreen()
            }

            is ActionResult.RequestCamera -> {
                loadScreen()
            }

            is ActionResult.BiometricUnlockOutcome -> {
                // Consumed by MainViewModel.retryInit(), which reports
                // the biometric hardware event and decodes the outcome
                // directly — it never flows through this screen pipeline.
            }

            is ActionResult.CompleteWith,
            is ActionResult.Unknown,
            -> { /* no-op */ }
        }
    }

    /**
     * Dispatch a list of [CommandDTO]s emitted by core. Called from
     * [applyResult] for `ActionResult.Commands`, and from the Phase 2b
     * envelope-drain path in [handleAction] / [navigateTo] /
     * [navigateBack] / [navigateToScreenWithParam] so the
     * lifecycle-emitted brightness / idle-timer commands reach the
     * exchange session and image-pick affordances reach the UI.
     *
     * ADR-031: hardware exchange commands (BLE, NFC, Audio, brightness,
     * idle-timer) are handled by the exchange session and the
     * MobileCommandHandler. Image picking commands are
     * dispatched to the UI layer via the `_imagePickEvent` flow.
     */
    private fun handleExchangeCommands(commands: List<CommandDTO>) {
        for (cmd in commands) {
            when (cmd) {
                is CommandDTO.ImagePickFromLibrary -> {
                    _imagePickEvent.value = "library"
                }

                is CommandDTO.ImageCaptureFromCamera -> {
                    _imagePickEvent.value = "camera"
                }

                is CommandDTO.ImagePickFromFile -> {
                    // File picking not supported on Android — report unavailable
                    sendHardwareEvent(
                        MobileEvent.HardwareUnavailable("ImagePickFromFile"),
                    )
                }

                is CommandDTO.SetScreenBrightness -> {
                    // Phase 2b screen-presentation lifecycle command.
                    // Mirrors `MultiStageExchangeEngine::screen_entered/exited`
                    // in core. Surface to the Activity-side collector via
                    // a typed StateFlow; the collector owns
                    // `Window.attributes.screenBrightness` and the
                    // snapshot/restore semantics.
                    _brightnessRequest.value =
                        cmd.level
                            ?.let { BrightnessRequest.Set(it) }
                            ?: BrightnessRequest.Restore
                }

                is CommandDTO.SetIdleTimerDisabled -> {
                    _idleTimerDisabledRequest.value = cmd.disabled
                }

                is CommandDTO.SetOrientationLock -> {
                    // Phase 2c screen-presentation lifecycle command.
                    // Surface to the Activity-side collector; the
                    // collector owns `Activity.requestedOrientation`.
                    _orientationLockRequest.value =
                        cmd.orientation
                            ?.let { OrientationLockRequest.Lock(it) }
                            ?: OrientationLockRequest.Restore
                }

                is CommandDTO.SwitchCamera -> {
                    // Camera-selector toggle from
                    // `MultiStageExchangeEngine`'s `switch_camera`
                    // action. The QR scanner Composable observes
                    // [useFrontCamera] via `LocalUseFrontCamera` and
                    // re-binds CameraX when the value changes.
                    _useFrontCamera.value = cmd.useFront
                }

                else -> {
                    // BLE, NFC, Audio commands handled by the in-process
                    // ExchangeCommandHandler attached to the
                    // MobileExchangeSession.
                }
            }
        }
    }

    companion object {
        private const val TAG = "CoreAppVM"
    }
}

/**
 * Screen-brightness request from core's `Command::SetScreenBrightness`
 * (Phase 2b screen-presentation lifecycle). Routed via
 * [CoreAppViewModel.brightnessRequest]; the Activity-side collector
 * owns the platform call.
 */
sealed interface BrightnessRequest {
    /** Set platform brightness to [level] (0.0–1.0). */
    data class Set(
        val level: Float,
    ) : BrightnessRequest

    /** Restore the platform-default brightness. */
    data object Restore : BrightnessRequest
}

/**
 * Composition-local exposing the active camera-selector preference
 * (front vs back) emitted by core's `Command::SwitchCamera`. Provided
 * at the [CoreScreenView] root and consumed by `QrCodeComponent`'s
 * scan-mode Composable. Default `false` (back camera) when no
 * provider is found, matching CameraX's
 * `CameraSelector.DEFAULT_BACK_CAMERA`.
 */
val LocalUseFrontCamera = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Orientation lock request from core's `Command::SetOrientationLock`
 * (Phase 2c screen-presentation lifecycle). Routed via
 * [CoreAppViewModel.orientationLockRequest]; the Activity-side
 * collector owns `Activity.requestedOrientation`.
 */
sealed interface OrientationLockRequest {
    /** Clamp the Activity to [orientation]. */
    data class Lock(
        val orientation: OrientationDTO,
    ) : OrientationLockRequest

    /** Restore the platform-default orientation behaviour. */
    data object Restore : OrientationLockRequest
}
