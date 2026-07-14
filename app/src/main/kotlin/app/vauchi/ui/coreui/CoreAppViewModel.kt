// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vauchi.ble.BleCommand
import app.vauchi.ble.BleFailure
import app.vauchi.camera.CameraFailure
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
import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAhaMoment
import uniffi.vauchi_platform.MobileAhaMomentType
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

    /**
     * Top-level tabs as core describes them — `id` (snake_case
     * `screen_id`), `label` (locale-resolved), `icon` (SF Symbol name
     * Android maps to a Material Icon), `badge_count`. Empty before
     * identity exists or before the first [loadTabs] call. Driven by
     * `PlatformAppEngine.navItems(MOBILE, locale)`; reload via [loadTabs] when
     * identity transitions or the user changes locale.
     */
    private val _tabs = MutableStateFlow<List<MobileTabInfo>>(emptyList())
    val tabs: StateFlow<List<MobileTabInfo>> = _tabs.asStateFlow()

    /**
     * A tab navigation requested before [loadTabs] populated [_tabs]
     * (cold-start/restore race, `2026-07-01-android-startup-nav-race-no-tab`).
     * Replayed by [flushPendingTabNav] once tabs arrive. Two startup
     * effects can each issue one (device-testing programmatic nav, then
     * the dynamic default landing) — the slot is FIRST-wins so the
     * explicit early nav beats the later default. Cleared by any deep
     * link: real intent supersedes the courtesy landing.
     */
    private var pendingTabNavId: String? = null

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _toastUndoActionId = MutableStateFlow<String?>(null)
    val toastUndoActionId: StateFlow<String?> = _toastUndoActionId.asStateFlow()

    private val _toastUndoLabel = MutableStateFlow<String?>(null)
    val toastUndoLabel: StateFlow<String?> = _toastUndoLabel.asStateFlow()

    private val _alertMessage = MutableStateFlow<Pair<String, String>?>(null)
    val alertMessage: StateFlow<Pair<String, String>?> = _alertMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openUrlEvent = MutableStateFlow<String?>(null)
    val openUrlEvent: StateFlow<String?> = _openUrlEvent.asStateFlow()

    fun consumeOpenUrlEvent() {
        _openUrlEvent.value = null
    }

    /**
     * Fires once when core reports onboarding is finished
     * (`ActionResult::OnboardingComplete`). The shell should flip app state
     * from onboarding to ready and render the current screen.
     * (`2026-07-06-mobile-domain-shell-violations` A13).
     */
    private val _onboardingCompleteEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onboardingCompleteEvent: SharedFlow<Unit> = _onboardingCompleteEvent.asSharedFlow()

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
     * Exchange-success ceremony request (M2 S5). Non-null when core emits
     * `Command::Celebrate`; the Compose layer performs the haptic and,
     * unless reduce-motion is enabled, plays the one-beat animation.
     */
    private val _celebrateRequest = MutableStateFlow<CelebrateRequest?>(null)
    val celebrateRequest: StateFlow<CelebrateRequest?> = _celebrateRequest.asStateFlow()

    fun consumeCelebrateRequest() {
        _celebrateRequest.value = null
    }

    /**
     * Aha-moment toast request tied to the exchange-success ceremony. When
     * `Command::Celebrate` arrives we also ask core for the
     * `firstContactAdded` milestone. If reduce-motion is enabled the Compose
     * layer surfaces this as a toast instead of the animated checkmark.
     */
    private val _ahaMomentRequest = MutableStateFlow<MobileAhaMoment?>(null)
    val ahaMomentRequest: StateFlow<MobileAhaMoment?> = _ahaMomentRequest.asStateFlow()

    fun consumeAhaMomentRequest() {
        _ahaMomentRequest.value = null
    }

    /**
     * File-pick requests emitted by core's `Command::FilePickFromUser`
     * (onboarding `restore_backup`, lost-device replacement, vCF
     * import). The Compose layer launches the system document picker
     * and reports back via [handleFilePicked] /
     * [handleFilePickCancelled]. See
     * `2026-06-11-android-restore-paths-all-dead`.
     */
    private val _filePickRequest = MutableStateFlow<FilePickRequest?>(null)
    val filePickRequest: StateFlow<FilePickRequest?> = _filePickRequest.asStateFlow()

    fun consumeFilePickRequest() {
        _filePickRequest.value = null
    }

    /**
     * True while a dispatched [UserAction] is executing in core. Hosts
     * render a blocking progress scrim once this has been true for a
     * beat — long-running engine work (e.g. a 10k-contact backup
     * restore) otherwise runs behind a fully interactive, unchanged
     * screen and users re-submit
     * (2026-06-11-restore-runs-without-progress-feedback).
     */
    private val _actionInFlight = MutableStateFlow(false)
    val actionInFlight: StateFlow<Boolean> = _actionInFlight.asStateFlow()

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
     * One-shot location capture for the exchange "where we met" annotation
     * (ADR-051 capture-at-exchange). Core emits `Command::LocationRequest` at
     * in-person exchange finalize; the Activity owns the request (LocationManager
     * + the runtime permission both need a Context) — the same split as audio /
     * accelerometer. Value = the requested timeout in ms; `null` = no request.
     */
    private val _locationRequest = MutableStateFlow<Long?>(null)
    val locationRequest: StateFlow<Long?> = _locationRequest.asStateFlow()

    fun consumeLocationRequest() {
        _locationRequest.value = null
    }

    /**
     * Forward the captured location outcome (built by LocationCaptureService:
     * `LocationResult`, `PermissionDenied`, or `HardwareUnavailable`) back to
     * core, which records it via `set_exchange_location` or falls back.
     */
    fun forwardLocationEvent(event: MobileEvent) {
        sendHardwareEvent(event)
    }

    /**
     * A BLE operation (scan / advertise / connect) failed to start. Reported
     * as PermissionDenied / HardwareUnavailable so the exchange engine fails
     * the flow visibly instead of waiting on a scan that never began.
     */
    fun onBleOperationFailed(error: String) {
        sendHardwareEvent(BleFailure.toEvent(error))
    }

    /**
     * The camera runtime permission was denied (the OS prompt resolved with a
     * deny). Reported as PermissionDenied("camera") so the exchange ledger /
     * CameraGate fails the QR leg visibly instead of leaving core waiting on a
     * scan that can never start (T0.3,
     * `2026-06-11-exchange-waits-forever-without-capabilities`).
     */
    fun onCameraPermissionDenied() {
        sendHardwareEvent(CameraFailure.deniedEvent())
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

    /**
     * Called by the Compose layer when the user picks a document for a
     * pending [FilePickRequest]. Routes the raw bytes + filename back
     * to core, which dispatches on the pick's purpose (e.g. backup
     * restore transitions onboarding to the password step).
     */
    fun handleFilePicked(
        bytes: ByteArray,
        filename: String,
    ) {
        sendHardwareEvent(MobileEvent.FilePickedFromUser(bytes = bytes, filename = filename))
    }

    /** Called when the user dismisses the document picker. */
    fun handleFilePickCancelled() {
        sendHardwareEvent(MobileEvent.FilePickCancelledByUser)
    }

    // FIFO, single consumer: hardware events must reach core in arrival
    // order (BLE KeyAck before card chunks) — a launch-per-event dispatch
    // re-ordered notifications arriving milliseconds apart. See
    // `_private/docs/problems/2026-06-06-android-ble-execution/`.
    private val hardwareEvents =
        FifoEventQueue<MobileEvent>(viewModelScope) { event ->
            deliverHardwareEvent(event)
        }

    private fun sendHardwareEvent(event: MobileEvent) {
        hardwareEvents.send(event)
    }

    private suspend fun deliverHardwareEvent(event: MobileEvent) {
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

    /**
     * Phase 2A/2B companion to iOS AppViewModel's event listener. Kept
     * as a property so its lifetime is bound to the ViewModel — if it
     * gets collected, UniFFI stops delivering callbacks.
     */
    private var eventListener: ScreenInvalidationListener? = null

    init {
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
     * Refresh [tabs] from `PlatformAppEngine.navItems(MOBILE, locale)`.
     * Call on startup, after identity creation (pre-identity returns
     * just Onboarding; post-identity returns the five mobile top-level
     * tabs), and whenever the active locale changes so labels stay in
     * sync. Errors are logged and leave the previous tabs in place.
     */
    fun loadTabs(locale: MobileLocale) {
        viewModelScope.launch {
            try {
                _tabs.value =
                    withContext(Dispatchers.IO) {
                        appEngine.navItems(layout = MobileTabLayout.MOBILE, locale = locale)
                    }
                flushPendingTabNav()
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
        // T0.3: the QR scanner reports a camera-permission denial as a sentinel
        // ActionPressed. Intercept it HERE — the single chokepoint every render
        // path's onAction funnels through (CoreScreenView AND CoreOnboardingScreen)
        // — and forward it to core as a hardware event, never as a serialized
        // UserAction (it must never reach handleActionJson / core's action path).
        // TODO(HUMBLE): T/W, P1. Mints/intercepts a sentinel action id for camera
        // denial instead of core emitting a dedicated hardware event. Fix: core
        // provides CameraPermissionDenied event or explicit action id.
        // (see _private problem record 2026-07-06-mobile-domain-shell-violations)
        if (action is UserAction.ActionPressed && action.actionId == CameraFailure.DENIED_ACTION_ID) {
            onCameraPermissionDenied()
            return
        }
        // Permissions step: when a mode is picked, surface the OS permissions
        // its ritual needs so the Activity can request them up front, before the
        // ritual screen. See _private/docs/problems/2026-06-06-exchange-ritual-flow/.
        // TODO(HUMBLE): D/T, P1. Parses "mode:" item ids and maps exchange mode
        // to Android permissions. Fix: core emits a Command::RequestPermissions
        // with capability list. (see _private problem record
        // 2026-07-06-mobile-domain-shell-violations)
        if (action is UserAction.ListItemSelected && action.itemId.startsWith("mode:")) {
            val perms = ExchangeModePermissions.forMode(action.itemId)
            if (perms.isNotEmpty()) {
                _modePermissionRequest.value = perms
            }
        }
        viewModelScope.launch {
            try {
                _actionInFlight.value = true
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
            } finally {
                _actionInFlight.value = false
            }
        }
    }

    /**
     * Forward a bottom-nav tab navigation by canonical tab id, looking up the
     * opaque `actionId` from the live [tabs] list and dispatching
     * [UserAction.NavigateToTab]. The id is treated opaquely — the shell does
     * not enumerate or interpret specific tab ids. Replaces
     * `navigateTo(screenName)` → `navigate_to_json` for tab targets so the
     * frontend stops constructing core screen names (ADR-043 Am4 §1;
     * CoreScreenIdMap rework).
     */
    fun navigateToTabById(canonicalId: String) {
        when (val decision = decideTabNav(_tabs.value, canonicalId)) {
            is TabNavDecision.Dispatch -> {
                handleAction(UserAction.NavigateToTab(actionId = decision.actionId))
            }

            is TabNavDecision.Queue -> {
                if (pendingTabNavId == null) {
                    pendingTabNavId = canonicalId
                }
            }

            is TabNavDecision.Unknown -> {
                Log.e(TAG, "navigateToTabById: no tab for id=$canonicalId")
            }
        }
    }

    /**
     * Replay a nav requested before tabs loaded. Called on [loadTabs]'
     * success path (a failed load keeps the request queued for the next
     * attempt). [decideTabNavFlush] owns the semantics: replay only while
     * the app still rests on core's bootstrap screen, keep queued while
     * tabs are still empty, drop silently when superseded by a real
     * navigation, and error only when tabs are loaded but the id is
     * genuinely absent.
     */
    private fun flushPendingTabNav() {
        val pending = pendingTabNavId ?: return
        when (val outcome = decideTabNavFlush(pending, _tabs.value, _screen.value?.navTabId)) {
            is TabNavFlush.Replay -> {
                pendingTabNavId = null
                handleAction(UserAction.NavigateToTab(actionId = outcome.actionId))
            }

            is TabNavFlush.Keep -> {
                Unit
            }

            is TabNavFlush.DropSuperseded -> {
                pendingTabNavId = null
            }

            is TabNavFlush.DropUnknown -> {
                pendingTabNavId = null
                Log.e(TAG, "navigateToTabById: no tab for id=$pending")
            }
        }
    }

    /**
     * Fires when core reports a back-stopping root via
     * `ActionResult::PerformNativeBack`. The Activity should finish / minimize
     * itself. One-shot StateFlow — consumed by [consumeNativeBackEvent].
     */
    private val _nativeBackEvent = MutableStateFlow<Boolean?>(null)
    val nativeBackEvent: StateFlow<Boolean?> = _nativeBackEvent.asStateFlow()

    fun consumeNativeBackEvent() {
        _nativeBackEvent.value = null
    }

    /**
     * Forward the system-back gesture to core as `UserAction::NavigateBack`.
     * Core returns `ActionResult::PerformNativeBack` when there is nothing to
     * pop; [nativeBackEvent] is emitted so the Activity can finish.
     */
    fun navigateBack() {
        handleAction(UserAction.NavigateBack)
    }

    /**
     * The shell's platform wakeup fired (Android WorkManager, lifecycle resume,
     * etc.). Runs the relay/exchange advance + activity-log poll and returns any
     * OS notifications plus commands. Core also fires `onScreensInvalidated`
     * through the attached listener when the tick changed the current screen.
     */
    suspend fun onWakeup(): WakeupOutcome {
        val outcomeJson =
            withContext(Dispatchers.IO) {
                appEngine.onWakeup()
            }
        val outcome = json.decodeFromString<WakeupOutcome>(outcomeJson)
        if (outcome.commands.isNotEmpty()) {
            handleExchangeCommands(outcome.commands)
        }
        return outcome
    }

    fun invalidateAll() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appEngine.invalidateAll() }
                loadScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invalidate", e)
            }
        }
    }

    fun dismissToast() {
        _toastMessage.value = null
        _toastUndoActionId.value = null
        _toastUndoLabel.value = null
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
        undoLabel: String? = null,
    ) {
        _toastMessage.value = message
        _toastUndoActionId.value = undoActionId
        _toastUndoLabel.value = undoLabel
    }

    fun dismissAlert() {
        _alertMessage.value = null
    }

    /**
     * Ask core to trigger an aha [momentType] and return the localized moment
     * if it should be shown now, or `null` if already seen. Errors are logged
     * and ignored — a missed milestone is non-fatal.
     */
    private fun tryTriggerAhaMoment(momentType: MobileAhaMomentType): MobileAhaMoment? =
        try {
            val result = appEngine.dispatchDomainCommand(DomainCommand.TryTriggerAhaMoment(momentType))
            (result as? DomainCommandResult.AhaMomentOpt)?.moment
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger aha moment $momentType", e)
            null
        }

    // Internal (not private) so the host-reachability guards can drive the
    // real `ActionResult` dispatch arms instead of a parallel helper
    // (`2026-06-11-silent-failure-mode-umbrella` goal 2).
    internal fun applyResult(result: ActionResult) {
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
                _toastUndoLabel.value = result.undoLabel
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

            is ActionResult.BackupExportComplete -> {
                // Core executed the backup — surface the data for sharing.
                // The encrypted hex is in result.data; emit to UI for save/share.
                _backupExportData.value = result.data
                loadScreen()
            }

            is ActionResult.OnboardingComplete -> {
                // Core has already navigated to the chosen post-onboarding
                // screen. Notify the shell so it flips app state from
                // onboarding to ready; then load the current screen so the
                // UI renders the destination.
                _onboardingCompleteEvent.tryEmit(Unit)
                loadScreen()
            }

            is ActionResult.PerformNativeBack -> {
                // Back-stopping root: the Activity finishes / minimizes itself.
                _nativeBackEvent.value = true
            }

            is ActionResult.BiometricUnlockOutcome -> {
                // Consumed by MainViewModel.retryInit(), which reports
                // the biometric hardware event and decodes the outcome
                // directly — it never flows through this screen pipeline.
            }

            // Resolved to NavigateTo/Commands by AppEngine.route_result in
            // core — frontends never observe these raw (ADR-043 Am4).
            // CompleteWith and StartDeviceLink are kept decode-only for
            // backward compatibility with older core versions.
            is ActionResult.OpenContact,
            is ActionResult.EditContact,
            is ActionResult.OpenEntryDetail,
            is ActionResult.CompleteWith,
            is ActionResult.StartDeviceLink,
            is ActionResult.RequestCamera,
            is ActionResult.Unknown,
            -> { /* no-op */ }
        }
    }

    /**
     * Dispatch a list of [CommandDTO]s emitted by core. Called from
     * [applyResult] for `ActionResult.Commands`, from [handleAction]'s
     * action-result envelope drain, and from [onWakeup] so lifecycle /
     * exchange commands reach the right platform handlers.
     *
     * ADR-031: hardware exchange commands (BLE, NFC, Audio, brightness,
     * idle-timer) are handled by the exchange session and the
     * MobileCommandHandler. Image picking commands are
     * dispatched to the UI layer via the `_imagePickEvent` flow.
     */
    private fun handleExchangeCommands(commands: List<CommandDTO>) {
        for (cmd in commands) {
            when (cmd) {
                is CommandDTO.ScheduleWakeup -> {
                    // Core hints when the next wakeup is due. Android uses a
                    // periodic WorkManager task, so no explicit re-arming here.
                }

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

                is CommandDTO.FilePickFromUser -> {
                    _filePickRequest.value =
                        FilePickRequest(
                            mimeTypes = cmd.acceptedMimeTypes,
                            purpose = cmd.purpose,
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

                is CommandDTO.LocationRequest -> {
                    _locationRequest.value = cmd.timeoutMs
                }

                is CommandDTO.Celebrate -> {
                    _celebrateRequest.value =
                        CelebrateRequest(
                            haptic = cmd.haptic,
                            sound = cmd.sound,
                            animation = cmd.animation,
                        )
                    // Mark the first-contact milestone as seen and stash the
                    // returned moment. CoreScreenView renders it as a toast
                    // when reduce-motion is enabled; otherwise the celebrate
                    // animation carries the moment and the toast is skipped.
                    _ahaMomentRequest.value = tryTriggerAhaMoment(MobileAhaMomentType.FIRST_CONTACT_ADDED)
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

                is CommandDTO.Unknown -> {
                    // A command this build can't decode (newer core, or a
                    // missing DTO arm like the FilePickFromUser gap). Answer
                    // with HardwareUnavailable so the engine sees the failure
                    // instead of waiting forever
                    // (2026-06-11-silent-failure-mode-umbrella).
                    Log.w(TAG, "Undecoded exchange command: ${cmd.variantName}")
                    sendHardwareEvent(MobileEvent.HardwareUnavailable(cmd.variantName))
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
 * Exchange-success ceremony request from core's `Command::Celebrate`
 * (M2 S5). The Compose layer performs the requested [haptic], plays
 * [animation] unless reduce-motion is enabled, and skips the [sound]
 * axis on Android (no bundled ceremony sound asset).
 */
data class CelebrateRequest(
    val haptic: String,
    val sound: String,
    val animation: String,
)

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

/**
 * Pending `Command::FilePickFromUser` for the Compose layer to fulfil
 * with the system document picker. [purpose] is the well-known core
 * variant name (`ImportBackup`, `ImportContacts`) or an `Other`
 * label key.
 */
data class FilePickRequest(
    val mimeTypes: List<String>,
    val purpose: String,
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
