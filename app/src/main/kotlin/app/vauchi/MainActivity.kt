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
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import app.vauchi.proximity.AccelerometerProximityService
import app.vauchi.proximity.AudioProximityService
import app.vauchi.proximity.LocationCaptureService
import app.vauchi.ui.AppPasswordScreen
import app.vauchi.ui.KeyInvalidatedRecoveryScreen
import app.vauchi.ui.MainViewModel
import app.vauchi.ui.MultiStageExchangeScreen
import app.vauchi.ui.NfcTapExchangeScreen
import app.vauchi.ui.QrDiagnosticScreen
import app.vauchi.ui.RecoveryScreen
import app.vauchi.ui.UiState
import app.vauchi.ui.coreui.BrightnessRequest
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreOnboardingScreen
import app.vauchi.ui.coreui.CoreScreenView
import app.vauchi.ui.coreui.MaterialIconName
import app.vauchi.ui.coreui.OrientationDTO
import app.vauchi.ui.coreui.OrientationLockRequest
import app.vauchi.ui.coreui.UserAction
import app.vauchi.ui.coreui.materialIconNameForCoreIcon
import app.vauchi.ui.theme.VauchiTheme
import app.vauchi.util.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.coreVersion

class MainActivity : FragmentActivity() {
    /** Mutable state for deep link URI, observed by Compose. */
    private val _deepLinkUri = mutableStateOf<Uri?>(null)

    /** Set by --reset-for-testing intent extra (DEBUG only). */
    private var _resetForTesting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                            .navigationBarsPadding(),
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

    override fun onStop() {
        super.onStop()
        // Trigger auto-lock if enabled when app goes to background (C1)
        try {
            val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
            viewModel.handleAppBackgrounded()
        } catch (e: Exception) {
            // viewModel might not be available or initialization failed
        }
    }

    private val _navigateTo = mutableStateOf<String?>(null)

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            _deepLinkUri.value = intent.data
        }

        // Poll for notifications off the main thread (E).
        // This triggers the native library load via pollNotifications() →
        // repository.platform(), so it must not block the first frame.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val viewModel = ViewModelProvider(this@MainActivity)[MainViewModel::class.java]
                val notifications = viewModel.pollNotifications()
                for (notification in notifications) {
                    app.vauchi.util.NotificationHelper
                        .showNotification(this@MainActivity, notification)
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

// Core screen_ids the shell renders with a NATIVE composable (hardware
// wrappers + the MyInfo TopAppBar chrome). Everything else renders via
// the generic CoreScreenView (dispatch inversion — CoreScreenIdMap rework).
private val NATIVE_SCREEN_IDS = setOf("my_info", "multi_stage_exchange", "exchange_nfc_role")

enum class Screen {
    // Pre-Ready boot/auth states. Home renders one of:
    // LoadingScreen / CoreOnboardingScreen / AuthenticationGate /
    // AppPasswordScreen / ErrorScreen / ReadyScreen, dispatched by
    // `UiState`. ReadyScreen is itself core-driven.
    Home,

    // Native screens awaiting per-pair retirement (Phase 2+ of
    // `2026-04-30-android-activity-enum-collapse`). Each one is its
    // own Pure Humble UI pair, sequenced low-risk-first.
    NfcExchange,
    Recovery,
    QrDiagnostic,

    // Hardware-presentation wrapper (orientation lock, brightness,
    // keep-screen-on) around a `CoreScreenView`. Stays native — not
    // a pure 1:1 shell, so the default core-driven render path doesn't
    // apply. ADR-031 hardware-event flow is the eventual migration.
    MultiStageExchange,
    // Pure Humble UI cases (Contacts, Settings, Devices, Labels,
    // ArchivedContacts, ContactMerge, DeviceReplacement, Help)
    // collapsed in Phase 1 — they render through the core-driven
    // dispatch above the `when (currentScreen)` block. ContactDetail
    // and LabelDetail were removed earlier (2026-04-28 audit
    // follow-up); core resolves the navigation to NavigateTo(ScreenModel)
    // in route_result and the frontend observes `coreAppViewModel.screen`.
}

/**
 * Resolve the SF-Symbol icon name from core's `MobileTabInfo.icon`
 * to a concrete Material `ImageVector`. Pure platform-presentation
 * (no logic), kept beside `MainScreen` so `androidx.compose.material`
 * imports stay in this file. The semantic mapping (which Material
 * Icon fits which SF Symbol) lives in
 * [app.vauchi.ui.coreui.materialIconNameForCoreIcon] so it can be
 * unit-tested without Compose.
 */
private fun imageVectorForCoreTab(coreIcon: String): ImageVector =
    when (materialIconNameForCoreIcon(coreIcon)) {
        MaterialIconName.PERSON -> Icons.Default.Person
        MaterialIconName.PEOPLE -> Icons.Default.People
        MaterialIconName.QR_CODE -> Icons.Default.QrCode
        MaterialIconName.GROUP -> Icons.Default.Group
        MaterialIconName.MORE_HORIZ -> Icons.Default.MoreHoriz
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
    val isOnline by viewModel.isOnline.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    // selectedContactId / selectedLabelId removed — contact-detail and
    // group-detail navigation are resolved in core (route_result emits
    // NavigateTo); the frontend just renders `coreAppViewModel.screen`.
    var showRestoreDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Core-driven screen renderer (SP-19)
    val coreAppViewModel =
        remember(viewModel) {
            CoreAppViewModel(viewModel.appEngine)
        }

    // Bottom-nav tabs come from core (`tabInfo(locale)`) — labels,
    // icons, and the tab set itself are core-owned. Reload when
    // identity is created (uiState transitions to Ready) and whenever
    // the active locale changes so labels stay in sync.
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val tabs by coreAppViewModel.tabs.collectAsState()
    LaunchedEffect(uiState, localizationManager.currentLocale) {
        if (uiState is UiState.Ready) {
            coreAppViewModel.loadTabs(localizationManager.currentLocale)
        }
    }

    // Handle OpenUrl events from core-driven screens
    val openUrlEvent by coreAppViewModel.openUrlEvent.collectAsState()
    LaunchedEffect(openUrlEvent) {
        openUrlEvent?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            coreAppViewModel.consumeOpenUrlEvent()
        }
    }

    // F2-NEW-7: surface the encrypted backup blob via Android's share
    // sheet so the user can route it to a file (Files / Drive / email
    // attachment / etc.). Without this, core's
    // `PlatformAppEngine.export_full_backup` returned the hex blob,
    // CoreAppViewModel staged it in `_backupExportData`, but no UI
    // consumer existed — the backup→restore round-trip was unreachable
    // through the shipping app. The blob is the encrypted backup
    // password protected by the user's chosen passphrase, so sending
    // it through the system share sheet is appropriate (the user
    // chooses the destination; core has already applied the
    // passphrase-derived encryption).
    val backupExportData by coreAppViewModel.backupExportData.collectAsState()
    LaunchedEffect(backupExportData) {
        backupExportData?.let { hex ->
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Vauchi backup")
                    putExtra(Intent.EXTRA_TEXT, hex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val chooser =
                Intent.createChooser(intent, "Save Vauchi backup").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(chooser)
            coreAppViewModel.consumeBackupExportData()
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

    // Permissions step (Group -> Mode -> Permissions -> Ritual): when the
    // ViewModel surfaces the OS permissions a freshly-selected mode needs
    // (camera / microphone / Bluetooth, per ExchangeModePermissions), request
    // any not already granted before the ritual screen. The camera is also
    // gated at the QR scanner itself; requesting up front keeps the ritual a
    // fast, uninterrupted handshake.
    val modePermissionRequest by coreAppViewModel.modePermissionRequest.collectAsState()
    val modePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { coreAppViewModel.consumeModePermissionRequest() }
    LaunchedEffect(modePermissionRequest) {
        val perms = modePermissionRequest
        if (perms.isEmpty()) return@LaunchedEffect
        val ungranted =
            perms.filter {
                ContextCompat.checkSelfPermission(context, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
        if (ungranted.isEmpty()) {
            coreAppViewModel.consumeModePermissionRequest()
        } else {
            modePermissionLauncher.launch(ungranted.toTypedArray())
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

                    override fun onConnected(deviceId: String) = coreAppViewModel.onBleConnected(deviceId)

                    override fun onDisconnected(reason: String) = coreAppViewModel.onBleDisconnected(reason)

                    override fun onCharacteristicNotified(
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicNotified(uuid, data)

                    override fun onCharacteristicRead(
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicRead(uuid, data)
                },
            )
        }
    val blePeripheral =
        remember {
            BlePeripheral(
                context,
                object : BlePeripheralListener {
                    override fun onConnected(deviceId: String) = coreAppViewModel.onBleConnected(deviceId)

                    override fun onDisconnected(reason: String) = coreAppViewModel.onBleDisconnected(reason)

                    override fun onCharacteristicReceived(
                        uuid: String,
                        data: ByteArray,
                    ) = coreAppViewModel.onBleCharacteristicNotified(uuid, data)
                },
            )
        }
    LaunchedEffect(Unit) {
        coreAppViewModel.bleCommands.collect { cmd ->
            when (cmd) {
                is BleCommand.StartScan -> {
                    bleCentral
                        .startScanning(cmd.serviceUuid)
                        ?.let { Log.w("MainActivity", "BLE scan: $it") }
                }

                is BleCommand.StartAdvertise -> {
                    // Core owns the tiebreak (ADR-043): cmd.payload is this
                    // device's identity-derived token; advertise it so the peer's
                    // core can compare and decide who connects.
                    blePeripheral
                        .startAdvertising(cmd.serviceUuid, cmd.payload)
                        ?.let { Log.w("MainActivity", "BLE advertise: $it") }
                }

                is BleCommand.Connect -> {
                    bleCentral
                        .connect(cmd.deviceId)
                        ?.let { Log.w("MainActivity", "BLE connect: $it") }
                }

                BleCommand.Disconnect -> {
                    bleCentral.disconnect()
                }

                is BleCommand.Write -> {
                    if (cmd.uuid in BleUuids.peripheralNotifyChars) {
                        blePeripheral.notify(cmd.uuid, cmd.data)
                    } else {
                        bleCentral.writeCharacteristic(cmd.uuid, cmd.data)
                    }
                }

                is BleCommand.Read -> {
                    bleCentral.readCharacteristic(cmd.uuid)
                }
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

    // Deep link consent gate (SP-9). The state machine + URL parser
    // live in core (`PlatformAppEngine.handleDeepLinkUri`) since the
    // 2026-04-25-deeplink-consent-orchestrator cleanup. The native
    // dialog is shown whenever core's current screen is the consent
    // gate — `screenId == "deep_link_consent"`.
    val coreScreen by coreAppViewModel.screen.collectAsState()
    val showDeepLinkConsent = coreScreen?.screenId == "deep_link_consent"

    // NOTE (Bug 2, `2026-05-30-exchange-screen-nav-visual-bugs`): an
    // earlier Activity-enum-collapse note claimed `MultiStageExchange`
    // renders through the default `CoreScreenView` arm so no core→local
    // mirror was needed. That was wrong — `coreScreenIdToVariant` returns
    // null for `exchange_*` (see `CoreScreenIdMap`), so the multi-stage
    // screen still renders through the local `when (currentScreen)` arm
    // below. Dropping the mirror left Cancel/Back frozen: core's
    // `navigate_back` changed core's screen but nothing popped the local
    // enum. The follow-core logic now lives in `MultiStageExchangeScreen`
    // itself (`onCoreNavigatedAway`), scoped to that composable's
    // lifetime, mirroring iOS's `FaceToFaceCoreShell.onChange`.

    // --reset-for-testing: create test identity so app skips onboarding (DEBUG only)
    LaunchedEffect(resetForTesting, uiState) {
        if (resetForTesting && uiState is UiState.Ready) {
            viewModel.seedTestIdentityIfNeeded()
        }
    }

    // Handle programmatic navigation (device testing: --es navigate exchange)
    // Must wait for UiState.Ready — auth must complete before navigating.
    LaunchedEffect(navigateTo, uiState) {
        if (navigateTo != null && uiState is UiState.Ready) {
            when (navigateTo) {
                "exchange" -> coreAppViewModel.navigateToTabById("exchange")

                "home" -> currentScreen = Screen.Home

                // Activity-enum collapse: Contacts and Settings render
                // through the default core-driven arm; navigate via core.
                "contacts" -> coreAppViewModel.navigateToTabById("contacts")

                "settings" -> coreAppViewModel.handleAction(UserAction.ActionPressed("open_settings"))
            }
            onNavigateConsumed()
        }
    }

    // Handle incoming deep link URI — forward raw URI string to core,
    // which parses it and (on success) navigates to the consent screen.
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            coreAppViewModel.handleDeepLinkUri(uri.toString()) { reason ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Invalid link: $reason")
                }
            }
            onDeepLinkConsumed()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && uiState is UiState.Ready) {
                    viewModel.sync()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Dynamic default screen: land on Contacts if user has contacts.
    // Activity-enum collapse: Contacts is no longer in the local enum;
    // route via core's nav so the default `CoreScreenView` arm renders.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is UiState.Ready && currentScreen == Screen.Home && state.contactCount > 0u) {
            coreAppViewModel.navigateToTabById("contacts")
        }
    }

    // The bottom nav is shown only on top-level screens. The active
    // tab id is core's published `screenId` (when it's one of the
    // top-level set), since core owns the navigation state of record
    // post-collapse. Native screens still in the local enum (Home,
    // MultiStageExchange, NfcExchange, etc.) keep their selection by
    // delegating through `coreScreen?.screenId` after navigation has
    // landed on a core screen, OR by leaving `activeTabId` null when
    // currently on a non-top-level native screen — both behaviours
    // are unchanged from the pre-collapse mapping.
    // Canonical tab the active screen belongs to (ADR-043 Am4), from
    // core's `current_tab_id` — supersedes the local `TOP_LEVEL_SCREEN_IDS`
    // / `canonicalScreenIdFor` fold, which went stale once core started
    // stamping canonical screen-ids (`contacts`/`groups`, not the
    // engine-emitted `contact_list`/`groups_list`). The bottom bar shows
    // only on tab *roots*: the active screen's own id equals its tab id.
    // Sub-screens report their parent tab but are not roots, so the bar
    // stays hidden there (unchanged behaviour).
    // `remember` keyed on the screen id so the synchronous engine
    // lookup runs only when navigation changes the screen — not on
    // every recomposition (which would hammer the UniFFI mutex on
    // the main thread).
    val currentTab = remember(coreScreen?.screenId) { coreAppViewModel.currentTabId() }
    val isTopLevel = currentTab != null && coreScreen?.screenId == currentTab

    // Follow-core: the native exchange wrappers (multi-stage QR, NFC tap)
    // are reached by core navigating to their screen_id; mirror that into
    // the local `currentScreen` so the matching `when` arm renders. Replaces
    // the retired ExchangeModePicker.onModeSelected sets. Leaving a native
    // wrapper resets to Home so the inverted CoreScreenView branch takes over.
    LaunchedEffect(coreScreen?.screenId) {
        when (coreScreen?.screenId) {
            "multi_stage_exchange" -> currentScreen = Screen.MultiStageExchange
            "exchange_nfc_role" -> currentScreen = Screen.NfcExchange
            else -> if (currentScreen == Screen.MultiStageExchange) currentScreen = Screen.Home
        }
    }

    // System BACK handling. Without an interceptor, every non-Home
    // screen would finish the Activity and exit the app — see problem
    // record `2026-05-21-android-back-stack-and-bottom-nav-broken`.
    //
    // Path A screens (Contacts, Groups, Settings, More, deep screens
    // routed through `CoreScreenView`): forward to core's
    // `navigateBack()` to pop the engine's nav history.
    //
    // Path B native screens (`Screen.MultiStageExchange` and friends):
    // route back to My Card. Setting `currentScreen` alone isn't
    // enough — core may be on `contact_list`, which would make Path A
    // win on the next recomposition; nudging the engine to `MyInfo`
    // keeps the local enum and core state consistent.
    //
    // Screens that own their own back gesture (NfcTap, Ble, etc.)
    // compose their own `BackHandler` lower in the tree; Compose
    // dispatches to the inner-most handler so those still win.
    // System BACK. Deep core screens pop via can_go_back()/navigateBack().
    // Core marks the bottom-nav tab roots (my_info, contacts, exchange,
    // groups, more) as back-stoppers, so can_go_back() is false there — but
    // a *secondary* tab root must still go Home (My Card), not exit the app
    // (the regression the old `coreVariantForBack != null` gate prevented).
    // `currentTab` is core's tab id for the active screen; only the home tab
    // (and the boot states) leave BACK to the OS.
    val coreCanGoBack = uiState is UiState.Ready && coreAppViewModel.canGoBack()
    val onSecondaryTab = currentTab != null && currentTab != "my_info"
    val canGoBack =
        uiState is UiState.Ready &&
            (coreCanGoBack || onSecondaryTab || currentScreen != Screen.Home)
    androidx.activity.compose.BackHandler(enabled = canGoBack) {
        if (coreCanGoBack) {
            coreAppViewModel.navigateBack()
        } else {
            currentScreen = Screen.Home
            coreAppViewModel.navigateToTabById("my_info")
        }
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel && uiState is UiState.Ready && tabs.isNotEmpty()) {
                NavigationBar {
                    for (tab in tabs) {
                        // Route the tap through core's nav; the default
                        // `CoreScreenView` arm picks up the resulting
                        // `coreScreen.screenId` and renders.
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = imageVectorForCoreTab(tab.icon),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            // `currentTab` is the canonical tab id core
                            // resolves for the active screen, so it compares
                            // directly to `tab.id` — no engine-id fold needed.
                            selected = currentTab == tab.id,
                            onClick = {
                                // Native top-level cases (my_info -> Home;
                                // exchange now routes through core's
                                // `exchange_mode_selection`) still need
                                // the local enum until their per-pair
                                // retirement, **and** also need to nudge
                                // core away from a deep screen — without the
                                // `navigateTo` the engine stays on (say)
                                // `contact_list` so Path A still wins and
                                // the tap appears to do nothing.
                                when (tab.id) {
                                    "my_info" -> currentScreen = Screen.Home
                                }
                                // Forward the opaque tab action_id as
                                // `UserAction::NavigateToTab` (ADR-043 Am4) —
                                // the typed action path, replacing the
                                // `navigate_to_json` round-trip so that surface
                                // can be retired (tier0-d Tier-0 cleanup).
                                coreAppViewModel.handleAction(
                                    UserAction.NavigateToTab(actionId = tab.actionId),
                                )
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Activity-enum-collapse Phase 1 dispatch: core wins when
            // it has navigated to one of the 7 Pure Humble UI screens
            // covered by `coreScreenIdToVariant`. The local `Screen`
            // enum below handles only the still-native screens
            // (hardware-aware MultiStageExchange, NFC, Recovery,
            // QrDiagnostic) and the pre-Ready boot
            // states (Home). Theme + language pickers retired Phase
            // 2a/A3a (`2026-05-01-android-humble-ui-deep-retirement`)
            // — they now live as `Component::Dropdown`s inside the
            // existing core-driven Settings screen.
            // Dispatch inversion (CoreScreenIdMap rework): render every core
            // screen via CoreScreenView by default; only the closed
            // NATIVE_SCREEN_IDS set (hardware wrappers + MyInfo chrome) falls
            // through to the local `when (currentScreen)` below. ADR-043: the
            // frontend no longer interprets screen_ids via a domain map.
            val sid = coreScreen?.screenId
            if (sid != null && sid !in NATIVE_SCREEN_IDS && uiState is UiState.Ready) {
                CoreScreenView(
                    viewModel = coreAppViewModel,
                    screenName = sid,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                when (currentScreen) {
                    Screen.Home -> {
                        when (val state = uiState) {
                            is UiState.Loading -> {
                                LoadingScreen()
                            }

                            is UiState.Onboarding -> {
                                CoreOnboardingScreen(
                                    coreAppViewModel = coreAppViewModel,
                                    onIdentityCreated = { viewModel.onCoreOnboardingComplete() },
                                )

                                if (showRestoreDialog) {
                                    RestoreIdentityDialog(
                                        onDismiss = { showRestoreDialog = false },
                                        onRestore = { backupData, password ->
                                            coroutineScope.launch {
                                                val success = viewModel.importFullBackup(backupData, password)
                                                if (success) {
                                                    showRestoreDialog = false
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            is UiState.Ready -> {
                                ReadyScreen(
                                    coreAppViewModel = coreAppViewModel,
                                    onSettings = { coreAppViewModel.handleAction(UserAction.ActionPressed("open_settings")) },
                                    isOnline = isOnline,
                                )
                            }

                            is UiState.AuthRequired -> {
                                AuthenticationGate(
                                    onAuthenticated = { viewModel.retryInit() },
                                    onError = { msg ->
                                        viewModel.setError(msg)
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
                                    message = state.message,
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
                                                val success = viewModel.importFullBackup(backupData, password)
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

                    Screen.MultiStageExchange -> {
                        MultiStageExchangeScreen(
                            coreAppViewModel = coreAppViewModel,
                            // When core navigates off multi_stage_exchange
                            // (Cancel/Back → navigate_back, or completion),
                            // follow it in the local enum. Without this the
                            // enum stays pinned here and the screen looks
                            // frozen (Bug 2,
                            // `2026-05-30-exchange-screen-nav-visual-bugs`).
                            onCoreNavigatedAway = {
                                currentScreen = Screen.Home
                            },
                        )
                    }

                    Screen.NfcExchange -> {
                        NfcTapExchangeScreen(coreAppViewModel = coreAppViewModel)
                    }

                    Screen.Recovery -> {
                        RecoveryScreen(
                            coreAppViewModel = coreAppViewModel,
                            onBack = { coreAppViewModel.navigateToTabById("more") },
                        )
                    }

                    Screen.QrDiagnostic -> {
                        // Guard with BuildConfig.DEBUG so R8 can tree-shake the
                        // real QrDiagnosticScreen out of release APKs. In release,
                        // the no-op stub from src/release/ is compiled instead and
                        // the condition evaluates to a compile-time false.
                        if (BuildConfig.DEBUG) {
                            QrDiagnosticScreen(
                                onBack = { coreAppViewModel.handleAction(UserAction.ActionPressed("open_settings")) },
                            )
                        } else {
                            coreAppViewModel.handleAction(UserAction.ActionPressed("open_settings"))
                        }
                    }

                    // The 8 Pure Humble UI cases collapsed in
                    // 2026-04-30-android-activity-enum-collapse Phase 1 +
                    // 1.1 (Contacts, Settings, Devices, Labels,
                    // ArchivedContacts, ContactMerge, DeviceReplacement,
                    // Help) plus More (Phase 2,
                    // 2026-05-01-more-engine-extension-android-retirement)
                    // render through the `if (coreVariant != null)` branch
                    // above. They no longer have a local `Screen` enum
                    // value.
                }
            }
        }
    } // Scaffold

    // Deep link consent dialog (SP-9). Visibility is driven by core —
    // the dialog is shown while `screenId == "deep_link_consent"` and
    // auto-hides when the user's grant/deny dispatch causes core to
    // navigate away. NEVER auto-process: the dialog forces an explicit
    // grant or deny ScreenAction press before any exchange can proceed.
    if (showDeepLinkConsent) {
        DeepLinkConsentDialog(
            onConfirm = { coreAppViewModel.handleAction(UserAction.ActionPressed("grant")) },
            onDeny = { coreAppViewModel.handleAction(UserAction.ActionPressed("deny")) },
        )
    }
}

/**
 * Consent dialog shown before processing a deep link exchange.
 *
 * This is the security gate: the user must explicitly confirm
 * before any exchange data from a deep link is processed.
 */
@Composable
fun DeepLinkConsentDialog(
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = localizationManager.t("deep_link.icon_a11y"),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(localizationManager.t("deep_link.title"))
        },
        text = {
            Text(localizationManager.t("deep_link.body"))
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(localizationManager.t("deep_link.accept"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(localizationManager.t("deep_link.decline"))
            }
        },
    )
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

// Phase 1B.1 (core-gui-architecture-alignment): the home (My Card)
// screen is now a thin Android shell around
// `CoreScreenView(screenName = "MyInfo")`. Core owns the card header,
// avatar, field list, add/edit/delete (via `form_dialog`), and the
// first-exchange prompt — see `core/vauchi-app/src/ui/my_info.rs`.
// The shell keeps the Android-specific chrome that isn't in the
// cross-platform ScreenModel: the Vauchi title bar, sync chip + settings
// icon, and the offline banner. Exchange / Contacts navigation is now
// handled entirely by the parent Scaffold's NavigationBar (driven by
// core's `tab_info`); the legacy bottom Exchange / Contacts pill
// shortcuts were dropped — they bypassed the mode picker (only
// reaching QR mode), duplicated the bottom-nav, and confused users
// about why the same destination had two different entry points.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyScreen(
    coreAppViewModel: CoreAppViewModel,
    onSettings: () -> Unit,
    isOnline: Boolean = true,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vauchi") },
                actions = {
                    // Sync status + trigger is core-driven: every top-level
                    // screen carries a `Component.Indicator(id="sync")` injected
                    // by core's `apply_sync_chrome_overlay` (tap → `sync_now`).
                    // The old app-bar `SyncStatusChip` duplicated it, so it was
                    // removed (2026-06-05-screen-ux-declutter). Humble UI: core
                    // owns sync presentation; the shell keeps only the gear.
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("home.settings")) {
                        Icon(Icons.Default.Settings, contentDescription = localizationManager.t("a11y.settings_icon"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (!isOnline) {
                OfflineBanner()
            }
            CoreScreenView(
                viewModel = coreAppViewModel,
                screenName = "MyInfo",
                modifier = Modifier.fillMaxSize(),
            )
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
    onError: (String) -> Unit,
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
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        ) {
                            onError("Authentication cancelled. Tap retry to unlock.")
                        } else {
                            onError("Authentication failed: $errString")
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
    message: String,
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val isLockScreenError =
        message.contains("lock screen", ignoreCase = true) ||
            message.contains("device authentication", ignoreCase = true)
    val isCancelledError = message.contains("cancelled", ignoreCase = true)

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
                        message
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
