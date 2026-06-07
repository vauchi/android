// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vauchi.ble.BleCommand
import app.vauchi.exchange.ExchangeModePermissions
import app.vauchi.nfc.NfcReaderPort
import app.vauchi.nfc.NfcReaderService
import app.vauchi.nfc.NfcResponderPort
import app.vauchi.nfc.VauchiHceResponder
import app.vauchi.nfc.dispatchNfcCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import uniffi.vauchi_platform.MobileTabLayout
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
    private val nfcReader: NfcReaderPort = NfcReaderService(),
    private val nfcResponder: NfcResponderPort = VauchiHceResponder(),
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
     * NFC reader-mode requests (T1.2). `true` asks the Activity to enable
     * `NfcAdapter.enableReaderMode` (initiator/"Send" side) so a tapped
     * peer surfaces via [onNfcTagDiscovered]; `false` disables it (on
     * NfcDeactivate / exchange teardown). `null` means "no pending request".
     */
    private val _nfcReaderModeRequest = MutableStateFlow<Boolean?>(null)
    val nfcReaderModeRequest: StateFlow<Boolean?> = _nfcReaderModeRequest.asStateFlow()

    fun consumeNfcReaderModeRequest() {
        _nfcReaderModeRequest.value = null
    }

    /**
     * Forward a tag discovered by the Activity's reader-mode callback to
     * the initiator reader (T1.2). The reader transceives the stashed
     * activate payload and surfaces the peer's response as a hardware event.
     */
    fun onNfcTagDiscovered(tag: Tag) {
        nfcReader.onTagDiscovered(tag)
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
     * TapHoverShake accelerometer-capture requests. `true` asks the Activity to
     * start streaming `Sensor.TYPE_ACCELEROMETER` readings (core emitted
     * `Command.AccelerometerStart` on the shake stage); `false` stops them
     * (`AccelerometerStop` / teardown). `null` means "no pending request". The
     * ViewModel has no `Context`, so the Activity owns the sensor (the same
     * split as the NFC reader-mode request).
     */
    private val _accelerometerActiveRequest = MutableStateFlow<Boolean?>(null)
    val accelerometerActiveRequest: StateFlow<Boolean?> =
        _accelerometerActiveRequest.asStateFlow()

    fun consumeAccelerometerRequest() {
        _accelerometerActiveRequest.value = null
    }

    /**
     * Per-mode permission requests. When the user selects an exchange mode, the
     * Android permissions that mode's ritual needs (camera / microphone /
     * Bluetooth, per [ExchangeModePermissions]) are surfaced here so the
     * Activity can request them up front — the "Permissions" step of the
     * Group → Mode → Permissions → Ritual flow. Empty means "no pending
     * request"; the ViewModel has no `Context`, so the Activity owns the
     * launcher (the same split as the accelerometer / NFC reader-mode requests).
     */
    private val _modePermissionRequest = MutableStateFlow<List<String>>(emptyList())
    val modePermissionRequest: StateFlow<List<String>> =
        _modePermissionRequest.asStateFlow()

    fun consumeModePermissionRequest() {
        _modePermissionRequest.value = emptyList()
    }

    /**
     * Ultrasonic audio-proximity requests (multi-stage modes: Hover / Magic /
     * TapHoverShake). Core emits `Command::AudioEmitChallenge` /
     * `AudioListenForResponse` / `AudioStop`; the Activity owns the
     * AudioRecord/AudioTrack (AudioProximityService needs a Context) — the same
     * split as the accelerometer / NFC requests. `null`/`false` = no request.
     */
    private val _audioEmitRequest = MutableStateFlow<AudioEmitRequest?>(null)
    val audioEmitRequest: StateFlow<AudioEmitRequest?> = _audioEmitRequest.asStateFlow()

    private val _audioListenRequest = MutableStateFlow<AudioListenRequest?>(null)
    val audioListenRequest: StateFlow<AudioListenRequest?> =
        _audioListenRequest.asStateFlow()

    private val _audioStopRequest = MutableStateFlow(false)
    val audioStopRequest: StateFlow<Boolean> = _audioStopRequest.asStateFlow()

    fun consumeAudioEmitRequest() {
        _audioEmitRequest.value = null
    }

    fun consumeAudioListenRequest() {
        _audioListenRequest.value = null
    }

    fun consumeAudioStopRequest() {
        _audioStopRequest.value = false
    }

    /**
     * BLE work items (Bump / Shake / Magic). Core emits BLE `Command`s; the
     * Activity owns the radio (BluetoothManager needs a Context) and dispatches
     * to BleCentral / BlePeripheral. A buffered SharedFlow (not a latest-only
     * StateFlow) preserves the rapid command sequence of a handshake.
     */
    private val _bleCommands = MutableSharedFlow<BleCommand>(extraBufferCapacity = 64)
    val bleCommands: SharedFlow<BleCommand> = _bleCommands.asSharedFlow()

    /** A peripheral advertising the vauchi service was discovered by the scan. */
    fun onBleDeviceDiscovered(
        id: String,
        rssi: Short,
        advData: ByteArray,
    ) {
        sendHardwareEvent(
            MobileEvent.BleDeviceDiscovered(id = id, rssi = rssi, advData = advData),
        )
    }

    /** A GATT connection (central or peripheral side) was established. */
    fun onBleConnected(deviceId: String) {
        sendHardwareEvent(MobileEvent.BleConnected(deviceId = deviceId))
    }

    /** The GATT connection dropped. */
    fun onBleDisconnected(reason: String) {
        sendHardwareEvent(MobileEvent.BleDisconnected(reason = reason))
    }

    /** Data received from the peer (central notification / peripheral write). */
    fun onBleCharacteristicNotified(
        uuid: String,
        data: ByteArray,
    ) {
        sendHardwareEvent(MobileEvent.BleCharacteristicNotified(uuid = uuid, data = data))
    }

    /** A characteristic read completed (central side). */
    fun onBleCharacteristicRead(
        uuid: String,
        data: ByteArray,
    ) {
        sendHardwareEvent(MobileEvent.BleCharacteristicRead(uuid = uuid, data = data))
    }

    /**
     * Forward ultrasonic samples captured by the Activity back to core as a
     * hardware event (response to `AudioListenForResponse`). Core matches the
     * captured response against the emitted challenge to verify co-presence.
     */
    fun onAudioSamplesRecorded(
        samples: List<Float>,
        sampleRate: UInt,
    ) {
        sendHardwareEvent(
            MobileEvent.AudioSamplesRecorded(samples = samples, sampleRate = sampleRate),
        )
    }

    /**
     * Forward one accelerometer reading captured by the Activity back to core
     * as a hardware event. Core accumulates the local shake envelope and
     * cross-correlates the peer's (`MultiStageSession`).
     */
    fun onAccelerometerData(
        timestampMs: Long,
        xMilliG: Int,
        yMilliG: Int,
        zMilliG: Int,
    ) {
        sendHardwareEvent(
            MobileEvent.AccelerometerData(
                timestampMs = timestampMs.toULong(),
                xMilliG = xMilliG,
                yMilliG = yMilliG,
                zMilliG = zMilliG,
            ),
        )
    }

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
                // core 0.51.44+: handleHardwareEvent returns the
                // `{"action_result": <ActionResult>|null, "commands": [<CommandDTO>]}`
                // envelope so hardware events deliver the Commands they produce
                // (KeyOffer / data writes / lifecycle hooks) — previously stranded.
                val envelope = json.decodeFromString<HardwareEventEnvelope>(resultJson)
                envelope.actionResult?.let { applyResult(it) }
                if (envelope.commands.isNotEmpty()) {
                    handleExchangeCommands(envelope.commands)
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

    /**
     * Drive one multi-stage protocol tick. Polling core advances the
     * engine-held machine and fires `onScreensInvalidated`, which
     * refetches the screen so the cycling own-QR and protocol progress
     * surface. The exchange screen calls this on a cadence via
     * [app.vauchi.ui.pollLoop] while composed — it replaces the core
     * cycle thread retired in slice-32m T1.2c, whose absence left the
     * own-QR never rendering (Bug 5,
     * `2026-05-30-exchange-screen-nav-visual-bugs`). Errors are logged,
     * not thrown — a dropped tick is recovered by the next one.
     */
    suspend fun tickMultiStageExchange() {
        withContext(Dispatchers.IO) {
            runCatching { appEngine.pollNotifications() }
                .onFailure { Log.e(TAG, "multi-stage tick poll failed", it) }
        }
    }

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

    /**
     * Canonical id of the bottom-nav tab the active screen belongs to
     * (ADR-043 Am4), or null for overlays. Drives bottom-nav visibility
     * and pill selection — supersedes the local `canonicalScreenIdFor` /
     * `TOP_LEVEL_SCREEN_IDS` fold now that core stamps canonical
     * screen-ids. A cheap synchronous engine lookup (not IO); called
     * during composition after a navigation has already settled the
     * engine state.
     */
    fun currentTabId(): String? = runCatching { appEngine.currentTabId(layout = MobileTabLayout.MOBILE) }.getOrNull()

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
        // Permissions step: when a mode is picked, surface the OS permissions
        // its ritual needs so the Activity can request them up front, before the
        // ritual screen. See _private/docs/problems/2026-06-06-exchange-ritual-flow/.
        if (action is UserAction.ListItemSelected && action.itemId.startsWith("mode:")) {
            val perms = ExchangeModePermissions.forMode(action.itemId)
            if (perms.isNotEmpty()) {
                _modePermissionRequest.value = perms
            }
        }
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

    /**
     * Forward a bottom-nav tab navigation by canonical tab id (`"contacts"`,
     * `"my_info"`, `"more"`, …), looking up the opaque `actionId` from the
     * live [tabs] list and dispatching [UserAction.NavigateToTab]. Replaces
     * `navigateTo(screenName)` → `navigate_to_json` for tab targets so the
     * frontend stops constructing core screen names (ADR-043 Am4 §1;
     * CoreScreenIdMap rework).
     */
    fun navigateToTabById(canonicalId: String) {
        val actionId = _tabs.value.firstOrNull { it.id == canonicalId }?.actionId
        if (actionId == null) {
            Log.e(TAG, "navigateToTabById: no tab for id=$canonicalId")
            return
        }
        handleAction(UserAction.NavigateToTab(actionId = actionId))
    }

    // / Whether core has somewhere to go back to from the current screen —
    // / either an AppScreen nav-history entry or an engine-internal step
    // / (e.g. an exchange sub-flow). The shell drives its BackHandler from
    // / this instead of a frontend-side screen-id map (ADR-043). Synchronous
    // / like [currentTabId]; a quick engine lock.
    fun canGoBack(): Boolean = runCatching { appEngine.canGoBack() }.getOrDefault(false)

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
     * drop a message into the same Snackbar pipeline. Shape mirrors
     * iOS `AppViewModel.showToast(...)`.
     */
    fun showToast(
        message: String,
        undoActionId: String? = null,
    ) {
        _toastMessage.value = message
        _toastUndoActionId.value = undoActionId
    }

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

            // Resolved to NavigateTo by AppEngine.route_result in core —
            // frontends never observe these raw (ADR-043 Am4).
            is ActionResult.OpenContact,
            is ActionResult.EditContact,
            is ActionResult.OpenEntryDetail,
            is ActionResult.CompleteWith,
            is ActionResult.Unknown,
            -> { /* no-op */ }
        }
    }

    /**
     * Dispatch a list of [CommandDTO]s emitted by core. Called from
     * [applyResult] for `ActionResult.Commands`, and from the Phase 2b
     * envelope-drain path in [handleAction] / [navigateTo] /
     * [navigateBack] so the
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

                is CommandDTO.AccelerometerStart -> {
                    // Ask the Activity to start streaming accelerometer readings
                    // for the shake stage; it owns the SensorManager and routes
                    // each reading back via [onAccelerometerData].
                    _accelerometerActiveRequest.value = true
                }

                is CommandDTO.AccelerometerStop -> {
                    _accelerometerActiveRequest.value = false
                }

                is CommandDTO.AudioEmitChallenge -> {
                    _audioEmitRequest.value =
                        AudioEmitRequest(samples = cmd.samples, sampleRate = cmd.sampleRate)
                }

                is CommandDTO.AudioListenForResponse -> {
                    _audioListenRequest.value =
                        AudioListenRequest(timeoutMs = cmd.timeoutMs, sampleRate = cmd.sampleRate)
                }

                is CommandDTO.AudioStop -> {
                    _audioStopRequest.value = true
                }

                is CommandDTO.BleStartScanning -> {
                    _bleCommands.tryEmit(BleCommand.StartScan(cmd.serviceUuid))
                }

                is CommandDTO.BleStartAdvertising -> {
                    _bleCommands.tryEmit(
                        BleCommand.StartAdvertise(
                            serviceUuid = cmd.serviceUuid,
                            payload = cmd.payload.map { it.toByte() }.toByteArray(),
                        ),
                    )
                }

                is CommandDTO.BleConnect -> {
                    _bleCommands.tryEmit(BleCommand.Connect(cmd.deviceId))
                }

                is CommandDTO.BleDisconnect -> {
                    _bleCommands.tryEmit(BleCommand.Disconnect)
                }

                is CommandDTO.BleWriteCharacteristic -> {
                    _bleCommands.tryEmit(
                        BleCommand.Write(
                            uuid = cmd.uuid,
                            data = cmd.data.map { it.toByte() }.toByteArray(),
                        ),
                    )
                }

                is CommandDTO.BleReadCharacteristic -> {
                    _bleCommands.tryEmit(BleCommand.Read(cmd.uuid))
                }

                else -> {
                    // NFC initiator command dispatch (T1.1). When core emits
                    // NfcActivate/NfcSendApdu/NfcDeactivate, relay to the
                    // reader and route its hardware events back to the engine.
                    // BLE / Audio and the HCE responder side remain deferred;
                    // initiator-vs-responder role negotiation is an open design
                    // question (`2026-05-29-nfc-exchange-mode-entry-wiring`).
                    val handled =
                        dispatchNfcCommand(
                            cmd,
                            nfcReader,
                            nfcResponder,
                            onReaderMode = { enable -> _nfcReaderModeRequest.value = enable },
                        ) { event ->
                            sendHardwareEvent(event)
                        }
                    if (!handled) {
                        Log.d(TAG, "Unhandled exchange command (dispatch deferred): $cmd")
                    }
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

/**
 * Ultrasonic emit request from core's `Command::AudioEmitChallenge`. The
 * Activity plays [samples] at [sampleRate] via AudioProximityService.
 */
data class AudioEmitRequest(
    val samples: List<Float>,
    val sampleRate: UInt,
)

/**
 * Ultrasonic listen request from core's `Command::AudioListenForResponse`. The
 * Activity records for [timeoutMs] ms at [sampleRate] and reports the captured
 * samples back via [CoreAppViewModel.onAudioSamplesRecorded].
 */
data class AudioListenRequest(
    val timeoutMs: Long,
    val sampleRate: UInt,
)

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
