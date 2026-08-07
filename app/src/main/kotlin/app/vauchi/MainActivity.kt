// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vauchi.ble.BleCentral
import app.vauchi.ble.BleCentralListener
import app.vauchi.ble.BleCommand
import app.vauchi.ble.BlePeripheral
import app.vauchi.ble.BlePeripheralListener
import app.vauchi.ble.BleUuids
import app.vauchi.exchange.ExchangeModePermissions
import app.vauchi.proximity.AccelerometerProximityService
import app.vauchi.proximity.AudioProximityService
import app.vauchi.proximity.LocationCaptureService
import app.vauchi.ui.AppPasswordScreen
import app.vauchi.ui.KeyInvalidatedRecoveryScreen
import app.vauchi.ui.MainViewModel
import app.vauchi.ui.StartupErrorKind
import app.vauchi.ui.UiState
import app.vauchi.ui.coreui.BrightnessRequest
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.OrientationDTO
import app.vauchi.ui.coreui.OrientationLockRequest
import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationHost
import app.vauchi.ui.startupErrorKindFor
import app.vauchi.ui.theme.VauchiTheme
import app.vauchi.util.LocalizationManager
import app.vauchi.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.MobileBleLinkDirection
import uniffi.vauchi_platform.MobilePendingNotification
import uniffi.vauchi_platform.coreVersion
import java.io.File

class MainActivity : FragmentActivity() {
    /** Mutable state for deep link URI, observed by Compose. */
    private val _deepLinkUri = mutableStateOf<Uri?>(null)

    /** Set by --reset-for-testing intent extra (DEBUG only). */
    private var _resetForTesting = false

    /** Notifications polled while POST_NOTIFICATIONS was not granted. */
    private val pendingNotifications = mutableListOf<MobilePendingNotification>()

    /** Launcher for the contextual POST_NOTIFICATIONS request. */
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    val batch = pendingNotifications.toList()
                    pendingNotifications.clear()
                    batch.forEach { notification ->
                        NotificationHelper.showNotification(this, notification)
                    }
                } else {
                    pendingNotifications.clear()
                }
            }

        // Prevent screenshots and screen recording (T1-5: screenshot prevention).
        // Disabled in debug builds for device testing automation (uiautomator).
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // Handle deep link from cold start
        handleIncomingIntent(intent)

        setContent {
            val deepLinkUri by _deepLinkUri
            val navigateTo by _navigateTo
            // Defer heavy MainScreen composition until after the first frame
            // renders. This eliminates the 34-frame skip from inflating the
            // entire navigation graph in a single Compose pass.
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { ready = true }
            VauchiTheme {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            // DEBUG-only: expose Compose `testTag`s as
                            // resource-ids so uiautomator can drive the app in
                            // device-test automation (same debug-only carve-out
                            // pattern as the screenshot-prevention guard in
                            // onCreate). No-op + no testid exposure in release.
                            .then(
                                if (BuildConfig.DEBUG) {
                                    Modifier.semantics { testTagsAsResourceId = true }
                                } else {
                                    Modifier
                                },
                            ),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (ready) {
                        MainScreen(
                            deepLinkUri = deepLinkUri,
                            onDeepLinkConsumed = { _deepLinkUri.value = null },
                            navigateTo = navigateTo,
                            onNavigateConsumed = { _navigateTo.value = null },
                            resetForTesting = _resetForTesting,
                        )
                    }
                }
            }
        }

        // Log build info on IO thread so native library load (11.9 MB .so)
        // doesn't block the first frame (D5 cold start < 2s).
        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(
                "Vauchi",
                "Build: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) core=${coreVersion()} buildId=${BuildConfig.BUILD_ID}",
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running (singleTask)
        handleIncomingIntent(intent)
    }

    private val _navigateTo = mutableStateOf<String?>(null)

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            _deepLinkUri.value = intent.data
        }

        // Poll for notifications off the main thread (E).
        // This triggers the native library load via pollNotifications() →
        // repository.ensureInitialized(), so it must not block the first frame.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val viewModel = ViewModelProvider(this@MainActivity)[MainViewModel::class.java]
                val notifications = viewModel.pollNotifications()
                if (notifications.isEmpty()) return@launch

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    // Android 12 and lower do not require POST_NOTIFICATIONS.
                    notifications.forEach { notification ->
                        NotificationHelper.showNotification(this@MainActivity, notification)
                    }
                    return@launch
                }

                val granted =
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    notifications.forEach { notification ->
                        NotificationHelper.showNotification(this@MainActivity, notification)
                    }
                } else {
                    // Stash the batch and request permission contextually on the
                    // main thread. The launcher callback will show or drop the
                    // batch based on the user's decision.
                    pendingNotifications.clear()
                    pendingNotifications.addAll(notifications)
                    runOnUiThread {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "pollAndShowNotifications failed", e)
            }
        }

        // Support direct navigation via: am start -n app.vauchi/.MainActivity --es navigate exchange
        // Used by device testing automation to open exchange screen programmatically.
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("navigate")?.let { target ->
                _navigateTo.value = target
            }
            // --reset-for-testing: create test identity so app skips onboarding.
            // Usage: adb shell am start -n app.vauchi/.MainActivity --ez reset_for_testing true
            if (intent?.getBooleanExtra("reset_for_testing", false) == true) {
                _resetForTesting = true
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    deepLinkUri: Uri? = null,
    onDeepLinkConsumed: () -> Unit = {},
    navigateTo: String? = null,
    onNavigateConsumed: () -> Unit = {},
    resetForTesting: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // The Android shell renders Core's immutable presentation snapshot and
    // forwards typed events. Domain navigation and action priority never live
    // in Compose.
    val coreAppViewModel =
        remember(viewModel) {
            CoreAppViewModel(
                appEngine = viewModel.appEngine,
                onPresentationCommitted = viewModel::reconcilePresentationState,
            )
        }

    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }

    // Handle OpenUrl events from core-driven screens
    val openUrlEvent by coreAppViewModel.openUrlEvent.collectAsState()
    LaunchedEffect(openUrlEvent) {
        openUrlEvent?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            coreAppViewModel.consumeOpenUrlEvent()
        }
    }

    // Core owns the file bytes, name, and MIME type. Android only materializes
    // them into its cache and opens the native share destination chooser.
    val exportFileRequest by coreAppViewModel.exportFileRequest.collectAsState()
    LaunchedEffect(exportFileRequest) {
        exportFileRequest?.let { request ->
            val safeName =
                request.suggestedName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { "vauchi-export" }
            val export = File(context.cacheDir, safeName)
            withContext(Dispatchers.IO) {
                export.writeBytes(request.data)
            }
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    export,
                )
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = request.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                }
            context.startActivity(
                Intent.createChooser(sendIntent, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            coreAppViewModel.consumeExportFileRequest()
        }
    }

    // Phase 2b screen-presentation: dispatch core's
    // `Command::SetScreenBrightness` / `Command::SetIdleTimerDisabled`
    // to the Activity window. Mirrors the prior `DisposableEffect`
    // inside `FaceToFaceExchangeScreen`, but driven by core's
    // `MultiStageExchangeEngine::screen_entered/screen_exited` so any
    // future screen with brightness needs (BleExchange, biometric,
    // etc.) gets the same behaviour for free.
    var savedBrightness by remember { mutableStateOf<Float?>(null) }
    val brightnessRequest by coreAppViewModel.brightnessRequest.collectAsState()
    LaunchedEffect(brightnessRequest) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        when (val req = brightnessRequest) {
            is BrightnessRequest.Set -> {
                val params = window.attributes
                if (savedBrightness == null) {
                    savedBrightness = params.screenBrightness
                }
                params.screenBrightness = req.level.coerceIn(0f, 1f)
                window.attributes = params
                coreAppViewModel.consumeBrightnessRequest()
            }

            BrightnessRequest.Restore -> {
                val params = window.attributes
                params.screenBrightness =
                    savedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
                savedBrightness = null
                coreAppViewModel.consumeBrightnessRequest()
            }

            null -> {
                Unit
            }
        }
    }
    val idleTimerRequest by coreAppViewModel.idleTimerDisabledRequest.collectAsState()
    LaunchedEffect(idleTimerRequest) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        when (idleTimerRequest) {
            true -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                coreAppViewModel.consumeIdleTimerDisabledRequest()
            }

            false -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                coreAppViewModel.consumeIdleTimerDisabledRequest()
            }

            null -> {
                Unit
            }
        }
    }

    // TapHoverShake shake co-location: start/stop accelerometer streaming on
    // core's `Command::AccelerometerStart`/`Stop`. The service samples
    // `TYPE_ACCELEROMETER` and routes each reading back through the ViewModel as
    // `Event::AccelerometerData`; core builds + correlates the envelope.
    val accelerometerRequest by coreAppViewModel.accelerometerActiveRequest.collectAsState()
    LaunchedEffect(accelerometerRequest) {
        val service = AccelerometerProximityService.getInstance(context)
        when (accelerometerRequest) {
            true -> {
                service.start { timestampMs, xMilliG, yMilliG, zMilliG ->
                    coreAppViewModel.onAccelerometerData(timestampMs, xMilliG, yMilliG, zMilliG)
                }
                coreAppViewModel.consumeAccelerometerRequest()
            }

            false -> {
                service.stop()
                coreAppViewModel.consumeAccelerometerRequest()
            }

            null -> {
                Unit
            }
        }
    }
    // Defensive: never leave the sensor registered if the screen is torn down
    // without an explicit AccelerometerStop (e.g. process death of the exchange).
    DisposableEffect(Unit) {
        onDispose { AccelerometerProximityService.getInstance(context).stop() }
    }

    // Phase 2c screen-presentation: dispatch core's
    // `Command::SetOrientationLock` to the Activity. Mirrors the prior
    // orientation `DisposableEffect` inside `FaceToFaceExchangeScreen`
    // (now retired) but driven by core's
    // `MultiStageExchangeEngine::screen_entered/screen_exited`. Any
    // future screen with orientation needs gets the same behaviour
    // for free.
    var savedOrientation by remember { mutableStateOf<Int?>(null) }
    val orientationRequest by coreAppViewModel.orientationLockRequest.collectAsState()
    LaunchedEffect(orientationRequest) {
        val activity = context as? Activity ?: return@LaunchedEffect
        when (val req = orientationRequest) {
            is OrientationLockRequest.Lock -> {
                if (savedOrientation == null) {
                    savedOrientation = activity.requestedOrientation
                }
                activity.requestedOrientation =
                    when (req.orientation) {
                        OrientationDTO.Portrait -> {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }

                        OrientationDTO.Landscape -> {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }
                coreAppViewModel.consumeOrientationLockRequest()
            }

            OrientationLockRequest.Restore -> {
                activity.requestedOrientation =
                    savedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                savedOrientation = null
                coreAppViewModel.consumeOrientationLockRequest()
            }

            null -> {
                Unit
            }
        }
    }

    // T1.2 — NFC reader-mode lifecycle. Core's initiator ("Send") path
    // emits Command::NfcActivate{non-empty}; the dispatcher requests
    // reader-mode (true). Enabling NfcAdapter reader-mode here routes a
    // tapped peer's tag to the engine via onNfcTagDiscovered; disabled on
    // NfcDeactivate (false) and on dispose (defensive — user backs out
    // mid-exchange). The HCE responder ("Receive") side needs no reader-mode.
    val nfcReaderModeRequest by coreAppViewModel.nfcReaderModeRequest.collectAsState()
    LaunchedEffect(nfcReaderModeRequest) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        when (nfcReaderModeRequest) {
            true -> {
                adapter?.enableReaderMode(
                    activity,
                    NfcAdapter.ReaderCallback { tag -> coreAppViewModel.onNfcTagDiscovered(tag) },
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                coreAppViewModel.consumeNfcReaderModeRequest()
            }

            false -> {
                adapter?.disableReaderMode(activity)
                coreAppViewModel.consumeNfcReaderModeRequest()
            }

            null -> {
                Unit
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.let { act ->
                // disableReaderMode throws IllegalStateException when the
                // Activity is already destroyed (rapid relaunch / config
                // change) — guard so teardown never crashes the app.
                try {
                    NfcAdapter.getDefaultAdapter(act)?.disableReaderMode(act)
                } catch (e: IllegalStateException) {
                    Log.w("MainActivity", "NFC disableReaderMode on dispose: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // Ultrasonic audio proximity (Hover / Magic / TapHoverShake). Core emits
    // AudioEmitChallenge / AudioListenForResponse / AudioStop; the Activity owns
    // AudioProximityService (AudioRecord/AudioTrack need a Context) - the same
    // split as the accelerometer requests.
    val audioEmitRequest by coreAppViewModel.audioEmitRequest.collectAsState()
    LaunchedEffect(audioEmitRequest) {
        val req = audioEmitRequest ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            AudioProximityService.getInstance(context).emitSignal(req.samples, req.sampleRate)
        }
        coreAppViewModel.consumeAudioEmitRequest()
    }
    val audioListenRequest by coreAppViewModel.audioListenRequest.collectAsState()
    LaunchedEffect(audioListenRequest) {
        val req = audioListenRequest ?: return@LaunchedEffect
        AudioProximityService.getInstance(context).receiveSignal(
            req.timeoutMs.toULong(),
            req.sampleRate,
        ) { samples, recordedRate ->
            coreAppViewModel.onAudioSamplesRecorded(samples, recordedRate)
        }
        coreAppViewModel.consumeAudioListenRequest()
    }
    val audioStopRequest by coreAppViewModel.audioStopRequest.collectAsState()
    LaunchedEffect(audioStopRequest) {
        if (audioStopRequest) {
            AudioProximityService.getInstance(context).stop()
            coreAppViewModel.consumeAudioStopRequest()
        }
    }

    // Location capture (ADR-051 "where we met"). Core emits LocationRequest at
    // in-person exchange finalize; capture a one-shot fix and report it back.
    // Mirrors iOS's inline CLLocationManager prompt: request the runtime
    // permission at capture time if not already granted, then let
    // LocationCaptureService build the resulting MobileEvent (it re-checks the
    // grant and emits PermissionDenied / HardwareUnavailable as needed).
    val locationRequest by coreAppViewModel.locationRequest.collectAsState()
    val pendingLocationTimeout = remember { mutableStateOf<Long?>(null) }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val timeout = pendingLocationTimeout.value
            pendingLocationTimeout.value = null
            if (timeout != null) {
                LocationCaptureService.getInstance(context).requestOneShot(timeout) { event ->
                    coreAppViewModel.forwardLocationEvent(event)
                }
            }
        }
    LaunchedEffect(locationRequest) {
        val timeout = locationRequest ?: return@LaunchedEffect
        coreAppViewModel.consumeLocationRequest()
        val locationPerms =
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        val granted =
            locationPerms.any {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        if (granted) {
            LocationCaptureService.getInstance(context).requestOneShot(timeout) { event ->
                coreAppViewModel.forwardLocationEvent(event)
            }
        } else {
            pendingLocationTimeout.value = timeout
            locationPermissionLauncher.launch(locationPerms)
        }
    }
    DisposableEffect(Unit) {
        onDispose { AudioProximityService.getInstance(context).stop() }
    }

    // BLE exchange (Bump / Shake / Magic) - slice S1: discovery. The Activity
    // owns the radio; core's BLE commands arrive over a buffered SharedFlow and
    // dispatch to BleCentral (scan) / BlePeripheral (advertise). Connect + GATT
    // land in S2/S3. See 2026-06-06-android-ble-execution.
    val bleCentral =
        remember {
            BleCentral(
                context,
                object : BleCentralListener {
                    override fun onDeviceDiscovered(
                        id: String,
                        rssi: Short,
                        advData: ByteArray,
                    ) = coreAppViewModel.onBleDeviceDiscovered(id, rssi, advData)

                    override fun onConnected(deviceId: String) = coreAppViewModel.onBleConnected(deviceId, MobileBleLinkDirection.OUTBOUND)

                    override fun onDisconnected(
                        deviceId: String,
                        reason: String,
                    ) = coreAppViewModel.onBleDisconnected(
                        deviceId,
                        MobileBleLinkDirection.OUTBOUND,
                        reason,
                    )

                    override fun onCharacteristicNotified(
                        deviceId: String,
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicNotified(
                        deviceId,
                        MobileBleLinkDirection.OUTBOUND,
                        uuid,
                        data,
                    )

                    override fun onCharacteristicRead(
                        deviceId: String,
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicRead(
                        deviceId,
                        MobileBleLinkDirection.OUTBOUND,
                        uuid,
                        data,
                    )
                },
            )
        }
    val blePeripheral =
        remember {
            BlePeripheral(
                context,
                object : BlePeripheralListener {
                    override fun onConnected(deviceId: String) = coreAppViewModel.onBleConnected(deviceId, MobileBleLinkDirection.INBOUND)

                    override fun onDisconnected(
                        deviceId: String,
                        reason: String,
                    ) = coreAppViewModel.onBleDisconnected(
                        deviceId,
                        MobileBleLinkDirection.INBOUND,
                        reason,
                    )

                    override fun onCharacteristicReceived(
                        deviceId: String,
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicNotified(
                        deviceId,
                        MobileBleLinkDirection.INBOUND,
                        uuid,
                        data,
                    )
                },
            )
        }

    fun executeBleCommand(cmd: BleCommand) {
        when (cmd) {
            is BleCommand.StartScan -> {
                bleCentral
                    .startScanning(cmd.serviceUuid)
                    ?.let {
                        Log.w("MainActivity", "BLE scan: $it")
                        coreAppViewModel.onBleOperationFailed(it)
                    }
            }

            is BleCommand.StartAdvertise -> {
                // Core owns the tiebreak (ADR-043): cmd.payload is this
                // device's identity-derived token; advertise it so the peer's
                // core can compare and decide who connects.
                blePeripheral
                    .startAdvertising(cmd.serviceUuid, cmd.payload)
                    ?.let {
                        Log.w("MainActivity", "BLE advertise: $it")
                        coreAppViewModel.onBleOperationFailed(it)
                    }
            }

            is BleCommand.Connect -> {
                bleCentral
                    .connect(cmd.deviceId)
                    ?.let {
                        Log.w("MainActivity", "BLE connect: $it")
                        coreAppViewModel.onBleOperationFailed(it)
                    }
            }

            is BleCommand.Disconnect -> {
                when (cmd.direction) {
                    MobileBleLinkDirection.OUTBOUND -> bleCentral.disconnect(cmd.deviceId)
                    MobileBleLinkDirection.INBOUND -> blePeripheral.disconnect(cmd.deviceId)
                }
            }

            is BleCommand.Write -> {
                when (cmd.direction) {
                    MobileBleLinkDirection.OUTBOUND -> {
                        bleCentral.writeCharacteristic(cmd.deviceId, cmd.uuid, cmd.data)
                    }

                    MobileBleLinkDirection.INBOUND -> {
                        blePeripheral.notify(cmd.deviceId, cmd.uuid, cmd.data)
                    }
                }
            }

            is BleCommand.Read -> {
                when (cmd.direction) {
                    MobileBleLinkDirection.OUTBOUND -> {
                        bleCentral.readCharacteristic(cmd.deviceId, cmd.uuid)
                    }

                    MobileBleLinkDirection.INBOUND -> {
                        coreAppViewModel.onBleOperationFailed(
                            "Inbound BLE characteristic reads are unsupported",
                        )
                    }
                }
            }
        }
    }

    // Android 12+ gates scan/advertise/connect behind runtime permissions, and
    // nothing was requesting them: core's BLE commands went straight to
    // BleCentral, which returned "Missing BLUETOOTH_SCAN permission" while the
    // user watched a scan that could never find anyone. Request at execution
    // time and replay the command that triggered the prompt — same shape as the
    // location capture above. Android 11 and below hid this because the legacy
    // ACCESS_FINE_LOCATION path *is* requested elsewhere.
    val blePermissions = remember { ExchangeModePermissions.bluetooth().toTypedArray() }
    val deferredBleCommands = remember { mutableStateListOf<BleCommand>() }
    val blePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            val replay = deferredBleCommands.toList()
            deferredBleCommands.clear()
            val denied = grants.filterValues { !it }.keys
            if (denied.isEmpty()) {
                replay.forEach { executeBleCommand(it) }
            } else {
                coreAppViewModel.onBleOperationFailed("Bluetooth permission denied: $denied")
            }
        }
    LaunchedEffect(Unit) {
        coreAppViewModel.bleCommands.collect { cmd ->
            val missing =
                blePermissions.filterNot {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
            if (missing.isEmpty()) {
                executeBleCommand(cmd)
                return@collect
            }
            // Queue first, then prompt only on the transition into an empty
            // queue: core emits scan and advertise back to back, and two
            // launches would stack two system dialogs on one decision.
            val alreadyPrompting = deferredBleCommands.isNotEmpty()
            deferredBleCommands.add(cmd)
            if (!alreadyPrompting) {
                blePermissionLauncher.launch(blePermissions)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            bleCentral.stopScanning()
            bleCentral.disconnect()
            blePeripheral.stopAdvertising()
        }
    }

    // --reset-for-testing: create test identity so app skips onboarding (DEBUG only).
    // Must also fire from `Onboarding` — a wiped (`pm clear`) device boots with no
    // identity straight to onboarding and never reaches `Ready`, so gating on Ready
    // alone left `reset_for_testing` a no-op on a truly fresh install (iOS seeds
    // unconditionally; this reaches parity). Seeding drives Onboarding → Ready.
    LaunchedEffect(resetForTesting, uiState) {
        if (resetForTesting && (uiState is UiState.Ready || uiState is UiState.Onboarding)) {
            viewModel.seedTestIdentityIfNeeded()
        }
    }

    // The debug launch hook remains accepted for device-test compatibility,
    // but navigation is no longer interpreted by the Android shell. A Core
    // debug event can replace this acknowledgement without reviving a local
    // screen enum.
    LaunchedEffect(navigateTo, uiState) {
        if (BuildConfig.DEBUG && navigateTo != null && uiState is UiState.Ready) {
            onNavigateConsumed()
        }
    }

    // Deep links are events; Core decides whether and where they navigate.
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            coreAppViewModel.dispatchPresentation(
                PresentationEvent.deepLinkOpened(uri.toString()),
            )
            onDeepLinkConsumed()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        coreAppViewModel.loadInitialPresentation()
                        coreAppViewModel.startForegroundHeartbeat()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        coreAppViewModel.dispatchPresentation(
                            PresentationEvent.appBackgrounded,
                        )
                        coreAppViewModel.stopForegroundHeartbeat()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            coreAppViewModel.stopForegroundHeartbeat()
        }
    }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Core emits this effect only when its own history is exhausted.
    val nativeBackEvent by coreAppViewModel.nativeBackEvent.collectAsState()
    LaunchedEffect(nativeBackEvent) {
        if (nativeBackEvent == true) {
            coreAppViewModel.consumeNativeBackEvent()
            (context as? Activity)?.finish()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is UiState.Loading -> {
                    LoadingScreen()
                }

                is UiState.Onboarding,
                is UiState.Ready,
                -> {
                    PresentationHost(
                        viewModel = coreAppViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is UiState.AuthRequired -> {
                    AuthenticationGate(
                        onAuthenticated = { viewModel.retryInit() },
                        onError = { kind, detail ->
                            viewModel.setError(kind, detail)
                        },
                    )
                }

                is UiState.AppPasswordRequired -> {
                    var authError by remember {
                        mutableStateOf<String?>(null)
                    }
                    AppPasswordScreen(
                        onAuthenticate = { pin ->
                            authError = null
                            viewModel.authenticateAppPassword(
                                pin,
                            ) { msg -> authError = msg }
                        },
                        onCancel = {
                            viewModel.cancelAppPassword()
                        },
                        errorMessage = authError,
                    )
                }

                is UiState.Error -> {
                    ErrorScreen(
                        kind = state.kind,
                        detail = state.detail,
                        onRetry = { viewModel.refresh() },
                    )
                }

                is UiState.KeyInvalidatedRecovery -> {
                    KeyInvalidatedRecoveryScreen(
                        onRestoreFromBackup = { showRestoreDialog = true },
                        onStartFresh = { viewModel.onRecoveryStartFresh() },
                    )

                    if (showRestoreDialog) {
                        RestoreIdentityDialog(
                            onDismiss = { showRestoreDialog = false },
                            onRestore = { backupData, password ->
                                coroutineScope.launch {
                                    val success =
                                        viewModel.importFullBackup(
                                            backupData,
                                            password,
                                        )
                                    if (success) {
                                        showRestoreDialog = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(localizationManager.t("app.loading"))
        }
    }
}

/**
 * Automatically triggers BiometricPrompt when KeyStore auth has expired.
 * Shows a loading screen while the prompt is displayed.
 */
@Composable
fun AuthenticationGate(
    onAuthenticated: () -> Unit,
    onError: (StartupErrorKind, String?) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val localizationManager = remember(activity) { LocalizationManager.getInstance(activity) }

    LaunchedEffect(Unit) {
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(localizationManager.t("auth.unlock.title"))
                .setSubtitle(localizationManager.t("auth.biometric_prompt_subtitle"))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ).build()

        val prompt =
            BiometricPrompt(
                activity,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onAuthenticated()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        when (val kind = startupErrorKindFor(errorCode)) {
                            StartupErrorKind.AuthCancelled -> onError(kind, null)

                            // errString is already OS-localized; pass it verbatim.
                            else -> onError(kind, errString.toString())
                        }
                    }
                },
            )
        prompt.authenticate(promptInfo)
    }

    // Show loading while biometric prompt is up
    LoadingScreen()
}

@Composable
fun ErrorScreen(
    kind: StartupErrorKind,
    detail: String? = null,
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val isLockScreenError = kind == StartupErrorKind.DeviceNotSecure
    val isCancelledError = kind == StartupErrorKind.AuthCancelled

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Warning,
                contentDescription = localizationManager.t("a11y.error_icon"),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text =
                    if (isLockScreenError) {
                        localizationManager.t("auth.device_lock_required")
                    } else if (isCancelledError) {
                        localizationManager.t("auth.required_title")
                    } else {
                        localizationManager.t("error.generic")
                    },
                style = MaterialTheme.typography.headlineMedium,
                color = if (isCancelledError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    if (isLockScreenError) {
                        localizationManager.t("auth.device_lock_required_body")
                    } else if (isCancelledError) {
                        localizationManager.t("auth.required_body")
                    } else {
                        detail ?: localizationManager.t("error.generic")
                    },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLockScreenError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = localizationManager.t("auth.device_lock_required_note"),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(localizationManager.t("action.open_settings"))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().testTag("error.retry"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = localizationManager.t("action.retry"))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(localizationManager.t("action.retry"))
                }
            } else {
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry, modifier = Modifier.testTag("error.retry")) {
                    Icon(Icons.Default.Refresh, contentDescription = localizationManager.t("action.retry"))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(localizationManager.t("action.retry"))
                }
            }
        }
    }
}

@Composable
fun OfflineBanner() {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = localizationManager.t("a11y.offline_icon"),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = localizationManager.t("sync.offline_banner"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

// Restore Identity Dialog
@Composable
fun RestoreIdentityDialog(
    onDismiss: () -> Unit,
    onRestore: (backupData: String, password: String) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    var backupData by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRestoring by remember { mutableStateOf(false) }

    val canRestore = backupData.isNotBlank() && password.isNotEmpty()

    AlertDialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        title = { Text(localizationManager.t("backup.restore_identity")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    localizationManager.t("backup.restore_body"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = backupData,
                    onValueChange = { backupData = it },
                    label = { Text(localizationManager.t("backup.data_label")) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    enabled = !isRestoring,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(localizationManager.t("backup.password")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isRestoring,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isRestoring = true
                    onRestore(backupData.trim(), password)
                },
                enabled = canRestore && !isRestoring,
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(localizationManager.t("action.restore"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRestoring,
            ) {
                Text(localizationManager.t("action.cancel"))
            }
        },
    )
}
