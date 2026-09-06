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
import app.vauchi.nfc.NfcReaderPort
import app.vauchi.nfc.NfcReaderService
import app.vauchi.nfc.NfcResponderPort
import app.vauchi.nfc.VauchiHceResponder
import app.vauchi.nfc.dispatchNfcCommand
import app.vauchi.ui.presentation.OverlayKind
import app.vauchi.ui.presentation.PresentationCommand
import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationProtocol
import app.vauchi.ui.presentation.PresentationReducer
import app.vauchi.ui.presentation.PresentationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAhaMoment
import uniffi.vauchi_platform.MobileAhaMomentType
import uniffi.vauchi_platform.MobileBleLinkDirection
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * ViewModel that bridges [PlatformAppEngine] to the generic Compose presentation host.
 *
 * Core owns immutable presentation state and reduces typed events into commands.
 * Android renders that state and performs only native effects. One instance is
 * shared by the entire host — one engine, one database connection.
 */
class CoreAppViewModel(
    private val appEngine: PlatformAppEngine,
    private val nfcReader: NfcReaderPort = NfcReaderService(),
    private val nfcResponder: NfcResponderPort = VauchiHceResponder(),
    private val onPresentationCommitted: () -> Unit = {},
    /**
     * Where this device is reachable on its current network, or `null` when
     * it is on none (ADR-070). Injected as a flow rather than a
     * `NetworkMonitor` so this stays free of a `Context` and testable.
     */
    private val localAddresses: Flow<String?> = emptyFlow(),
) : ViewModel() {
    init {
        // Core cannot enumerate interfaces (ADR-030/031), so it learns where
        // we are only because the shell reports it. Collected for the
        // ViewModel's whole life rather than per foreground: the network can
        // change while a link ceremony is on screen, and a stale address
        // would point a joiner somewhere it cannot reach.
        viewModelScope.launch {
            localAddresses.collect { address ->
                deliverHardwareEvent(MobileEvent.LocalNetworkAddressChanged(address))
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val presentationMutex = Mutex()

    private val _presentationState = MutableStateFlow(PresentationState())
    val presentationState: StateFlow<PresentationState> =
        _presentationState.asStateFlow()

    /**
     * Foreground app-heartbeat driver (ADR-044 Am2a). Core owns *when* the
     * next wakeup is due and re-arms via `Command::ScheduleWakeup`; the shell
     * owns only the native timer. iOS uses a foreground `DispatchSourceTimer`
     * (`WakeupService`); Android services the SHORT foreground cadence here
     * with a coroutine loop, reserving WorkManager (`SyncWorker`, ~15-min
     * floor + Doze) for background/long-horizon wakeups. Without a foreground
     * driver the BLE stall deadline (`ble_engine.rs` `BLE_STEP_TIMEOUT_SECS =
     * 60`) never trips in the foreground, so a stalled exchange renders
     * "Exchanging…" forever with no timeout/cancel (device pass 2026-07-22;
     * `problems/2026-06-11-exchange-waits-forever-without-capabilities`).
     */
    private var foregroundWakeupJob: Job? = null

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _alertMessage = MutableStateFlow<Pair<String, String>?>(null)
    val alertMessage: StateFlow<Pair<String, String>?> = _alertMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openUrlEvent = MutableStateFlow<String?>(null)
    val openUrlEvent: StateFlow<String?> = _openUrlEvent.asStateFlow()

    fun consumeOpenUrlEvent() {
        _openUrlEvent.value = null
    }

    private val _exportFileRequest =
        MutableStateFlow<ExportFileRequest?>(null)
    val exportFileRequest: StateFlow<ExportFileRequest?> =
        _exportFileRequest.asStateFlow()

    fun consumeExportFileRequest() {
        _exportFileRequest.value = null
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
     * True while a presentation event is being reduced by core. Hosts
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

    /**
     * A GATT connection was established. `direction` is the physical link role:
     * the central (dialed out) reports `OUTBOUND`, the peripheral (connected to)
     * reports `INBOUND`. Core derives the handshake role from it (F0).
     */
    fun onBleConnected(
        deviceId: String,
        direction: MobileBleLinkDirection,
    ) {
        sendHardwareEvent(MobileEvent.BleConnected(deviceId = deviceId, direction = direction))
    }

    /** One addressed GATT link dropped. */
    fun onBleDisconnected(
        deviceId: String,
        direction: MobileBleLinkDirection,
        reason: String,
    ) {
        sendHardwareEvent(
            MobileEvent.BleDisconnected(
                deviceId = deviceId,
                direction = direction,
                reason = reason,
            ),
        )
    }

    /** Data received from the peer (central notification / peripheral write). */
    fun onBleCharacteristicNotified(
        deviceId: String,
        direction: MobileBleLinkDirection,
        uuid: String,
        data: ByteArray,
    ) {
        sendHardwareEvent(
            MobileEvent.BleCharacteristicNotified(
                deviceId = deviceId,
                direction = direction,
                uuid = uuid,
                data = data,
            ),
        )
    }

    /** A characteristic read completed (central side). */
    fun onBleCharacteristicRead(
        deviceId: String,
        direction: MobileBleLinkDirection,
        uuid: String,
        data: ByteArray,
    ) {
        sendHardwareEvent(
            MobileEvent.BleCharacteristicRead(
                deviceId = deviceId,
                direction = direction,
                uuid = uuid,
                data = data,
            ),
        )
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
            presentationMutex.withLock {
                applyPresentationEnvelope(resultJson)
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
        loadInitialPresentation()
        attachEventListener()
    }

    fun loadInitialPresentation() {
        viewModelScope.launch {
            try {
                presentationMutex.withLock {
                    val commandJson =
                        withContext(Dispatchers.IO) {
                            appEngine.initialCommandsJson()
                        }
                    applyPresentationEnvelope(commandJson)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load presentation", e)
                _error.value = "Failed to load presentation: ${e.message}"
            }
        }
    }

    fun dispatchPresentation(event: PresentationEvent) {
        dispatchPresentationEvents(event)
    }

    fun activateAndDispatch(
        surfaceId: String,
        event: PresentationEvent,
    ) {
        dispatchPresentationEvents(
            PresentationEvent.SurfaceActivated(surfaceId),
            event,
        )
    }

    fun dismissPresentationOverlay() {
        val overlay = _presentationState.value.overlay ?: return
        _presentationState.value =
            _presentationState.value.copy(overlay = null)
        dispatchPresentation(
            PresentationEvent.OverlayDismissed(
                surfaceId = overlay.surfaceId,
                kind = overlay.overlay.kind,
            ),
        )
    }

    private fun dispatchPresentationEvents(vararg events: PresentationEvent) {
        viewModelScope.launch {
            try {
                _actionInFlight.value = true
                presentationMutex.withLock {
                    for (event in events) {
                        val commandJson =
                            withContext(Dispatchers.IO) {
                                appEngine.dispatchJson(eventJson = event.toJson())
                            }
                        applyPresentationEnvelope(commandJson)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch presentation event", e)
                _error.value = "Presentation failed: ${e.message}"
            } finally {
                _actionInFlight.value = false
            }
        }
    }

    private fun applyPresentationEnvelope(commandJson: String) {
        val envelope = PresentationProtocol.decodeEnvelope(commandJson)
        val result =
            PresentationReducer.apply(
                _presentationState.value,
                envelope.commands,
            )
        _presentationState.value = result.state
        handlePresentationEffects(result.effects)
        onPresentationCommitted()
    }

    private fun handlePresentationEffects(effects: List<PresentationCommand.Effect>) {
        for (effect in effects) {
            when (effect.variant) {
                "PresentAlert" -> {
                    val alert =
                        effect.payload.jsonObject
                            .getValue("alert")
                            .jsonObject
                    _alertMessage.value =
                        alert.getValue("title").jsonPrimitive.content to
                        alert.getValue("message").jsonPrimitive.content
                }

                "ShowToast" -> {
                    val toast =
                        effect.payload.jsonObject
                            .getValue("toast")
                            .jsonObject
                    showToast(toast.getValue("message").jsonPrimitive.content)
                }

                "OpenExternalUrl" -> {
                    _openUrlEvent.value =
                        effect.payload.jsonObject
                            .getValue("url")
                            .jsonPrimitive
                            .content
                }

                "ExportFile" -> {
                    val file =
                        effect.payload.jsonObject
                            .getValue("file")
                            .jsonObject
                    _exportFileRequest.value =
                        ExportFileRequest(
                            suggestedName =
                                file
                                    .getValue("suggested_name")
                                    .jsonPrimitive
                                    .content,
                            mimeType =
                                file
                                    .getValue("mime_type")
                                    .jsonPrimitive
                                    .content,
                            data =
                                file
                                    .getValue("data")
                                    .jsonArray
                                    .map {
                                        it.jsonPrimitive.content
                                            .toInt()
                                            .toByte()
                                    }.toByteArray(),
                        )
                }

                "PerformNativeBack" -> {
                    _nativeBackEvent.value = true
                }

                "ResetApplication" -> {
                    loadInitialPresentation()
                }

                "PostNotification" -> {
                    Log.d(TAG, "Notification effect delegated to native scheduler")
                }

                else -> {
                    val wrapped =
                        JsonObject(
                            mapOf(effect.variant to effect.payload),
                        )
                    val command =
                        runCatching {
                            json.decodeFromJsonElement<CommandDTO>(wrapped)
                        }.getOrElse {
                            CommandDTO.Unknown(effect.variant)
                        }
                    handleExchangeCommands(listOf(command))
                }
            }
        }
    }

    private fun attachEventListener() {
        val listener =
            ScreenInvalidationListener {
                // Core may fire this on the same thread that dispatched an
                // event. Hopping back into the engine there
                // would deadlock the internal Mutex — bounce through
                // viewModelScope first.
                viewModelScope.launch {
                    dispatchPresentation(PresentationEvent.presentationInvalidated)
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

    /** Core requested native back after reducing a generic back event. */
    private val _nativeBackEvent = MutableStateFlow<Boolean?>(null)
    val nativeBackEvent: StateFlow<Boolean?> = _nativeBackEvent.asStateFlow()

    fun consumeNativeBackEvent() {
        _nativeBackEvent.value = null
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
        loadInitialPresentation()
        return outcome
    }

    /**
     * Start the foreground heartbeat loop (call on foreground / `ON_RESUME`).
     * Idempotent — a no-op if already running. Each tick runs [onWakeup]
     * (polling core: advancing the exchange stall deadline and posting any due
     * notifications), then waits the core-dictated interval from the emitted
     * `ScheduleWakeup` before the next tick, until [stopForegroundHeartbeat] or
     * `viewModelScope` cancellation. See [foregroundWakeupJob] for why this is
     * required (WorkManager cannot service a sub-minute foreground deadline).
     */
    fun startForegroundHeartbeat() {
        if (foregroundWakeupJob?.isActive == true) return
        foregroundWakeupJob =
            viewModelScope.launch {
                while (isActive) {
                    // Milliseconds, because a live QR exchange advances its
                    // display from this loop: whole seconds pinned it at one
                    // frame per second against a ~300 ms design, while the
                    // peer's camera decodes ~30 frames per second
                    // (device-measured 2026-08-19). `earliestMillis` is absent
                    // for the idle heartbeat, which stays on whole seconds.
                    val tickStart = System.currentTimeMillis()
                    val nextMillis =
                        try {
                            val scheduled =
                                onWakeup()
                                    .commands
                                    .filterIsInstance<CommandDTO.ScheduleWakeup>()
                                    .firstOrNull()
                            scheduled?.earliestMillis?.toLong()
                                ?: scheduled?.earliestSecs?.toLong()?.times(1000L)
                                ?: (DEFAULT_FOREGROUND_WAKEUP_SECS * 1000L)
                        } catch (e: Exception) {
                            Log.e(TAG, "Foreground wakeup tick failed", e)
                            DEFAULT_FOREGROUND_WAKEUP_SECS * 1000L
                        }
                    // The exchange QR advances from this loop, so its period is
                    // the tick's own cost plus the delay — not the delay alone.
                    // On device the display moved every 2-4 s while core asked
                    // for ~300 ms, and only measuring both parts says which one
                    // is responsible
                    // (2026-08-18-hover-transfer-stalls-on-the-last-chunk).
                    val tickMs = System.currentTimeMillis() - tickStart
                    if (tickMs > 200 || nextMillis < 1000L) {
                        Log.i(TAG, "[MSX] wakeup tick=${tickMs}ms next=${nextMillis}ms")
                    }
                    delay(nextMillis)
                }
            }
    }

    /**
     * Stop the foreground heartbeat (call on background / `ON_STOP`). Background
     * wakeups ride WorkManager; keeping a sub-minute loop alive off-screen would
     * drain battery for no benefit.
     */
    fun stopForegroundHeartbeat() {
        foregroundWakeupJob?.cancel()
        foregroundWakeupJob = null
    }

    fun invalidateAll() {
        dispatchPresentation(PresentationEvent.presentationInvalidated)
    }

    fun dismissToast() {
        _toastMessage.value = null
    }

    /**
     * Surface a transient toast through the native Snackbar pipeline.
     */
    fun showToast(message: String) {
        _toastMessage.value = message
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

    /**
     * Dispatch native [CommandDTO] effects emitted by core presentation or
     * hardware-event processing.
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
                    // Foreground re-arm is consumed by the startForegroundHeartbeat
                    // loop, which reads this interval from the wakeup outcome and
                    // schedules the next tick. Background wakeups ride WorkManager
                    // (SyncWorker). Nothing to do here.
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
                    Log.i(
                        "Vauchi",
                        "[QrCamera] SwitchCamera cmd useFront=${cmd.useFront} " +
                            "(was ${_useFrontCamera.value})",
                    )
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
                    _bleCommands.tryEmit(
                        BleCommand.Disconnect(
                            deviceId = cmd.deviceId,
                            direction = cmd.direction.toMobileDirection(),
                        ),
                    )
                }

                is CommandDTO.BleWriteCharacteristic -> {
                    _bleCommands.tryEmit(
                        BleCommand.Write(
                            deviceId = cmd.deviceId,
                            direction = cmd.direction.toMobileDirection(),
                            uuid = cmd.uuid,
                            data = cmd.data.map { it.toByte() }.toByteArray(),
                        ),
                    )
                }

                is CommandDTO.BleReadCharacteristic -> {
                    _bleCommands.tryEmit(
                        BleCommand.Read(
                            deviceId = cmd.deviceId,
                            direction = cmd.direction.toMobileDirection(),
                            uuid = cmd.uuid,
                        ),
                    )
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
                    // NFC command dispatch. When core emits NfcActivate/
                    // NfcSendApdu/NfcDeactivate, relay to the reader (initiator)
                    // or HCE responder and route hardware events back to the
                    // engine. Both sides landed — only the two-device hardware
                    // acceptance gate remains open
                    // (`2026-05-29-nfc-exchange-mode-entry-wiring`, status
                    // `testing`).
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

        /**
         * Fallback foreground heartbeat cadence if core emits no
         * `ScheduleWakeup` interval (core's `compute_next_wakeup` default
         * is 30 s).
         */
        private const val DEFAULT_FOREGROUND_WAKEUP_SECS = 30L
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

private fun BleLinkDirectionDTO.toMobileDirection(): MobileBleLinkDirection =
    when (this) {
        BleLinkDirectionDTO.Outbound -> MobileBleLinkDirection.OUTBOUND
        BleLinkDirectionDTO.Inbound -> MobileBleLinkDirection.INBOUND
    }
