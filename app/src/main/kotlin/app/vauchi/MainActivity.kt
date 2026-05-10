// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vauchi.deeplink.LinkResponderSessionService
import app.vauchi.ui.AppPasswordScreen
import app.vauchi.ui.BleExchangeScreen
import app.vauchi.ui.ContactDetailScreen
import app.vauchi.ui.ExchangeMode
import app.vauchi.ui.ExchangeModePicker
import app.vauchi.ui.KeyInvalidatedRecoveryScreen
import app.vauchi.ui.MainViewModel
import app.vauchi.ui.MultiStageExchangeScreen
import app.vauchi.ui.NfcExchangeScreen
import app.vauchi.ui.QrDiagnosticScreen
import app.vauchi.ui.RecoveryScreen
import app.vauchi.ui.SyncState
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
import uniffi.vauchi_platform.coreVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

enum class Screen {
    // Pre-Ready boot/auth states. Home renders one of:
    // LoadingScreen / CoreOnboardingScreen / AuthenticationGate /
    // AppPasswordScreen / ErrorScreen / ReadyScreen, dispatched by
    // `UiState`. ReadyScreen is itself core-driven.
    Home,

    // Native screens awaiting per-pair retirement (Phase 2+ of
    // `2026-04-30-android-activity-enum-collapse`). Each one is its
    // own Pure Humble UI pair, sequenced low-risk-first.
    ExchangeModePicker,
    NfcExchange,
    BleExchange,
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
    // follow-up); they navigate via
    // `coreAppViewModel.navigateToScreenWithParam(...)` and observe
    // `coreAppViewModel.screen` directly.
}

/**
 * Map a snake_case core screen id (`AppScreen::screen_id()` output)
 * to the PascalCase `AppScreen` variant name that
 * [CoreAppViewModel.navigateTo] accepts. Returns `null` for ids that
 * either don't map to a Pure Humble UI screen (native cases handled
 * by their dedicated [Screen] enum arms) or aren't recognised.
 *
 * The original 7 ids mirror the cases removed from `Screen` in the
 * 2026-04-30 Activity-enum-collapse Phase 1 — they all render through
 * the default `CoreScreenView` path. `MultiStageExchange` stays
 * native because it's a hardware-presentation wrapper (orientation
 * lock, brightness, keep-screen-on) around a `CoreScreenView`, not a
 * pure 1:1 shell. `decoy_contacts` was added in Phase 2c of
 * `2026-05-01-android-humble-ui-deep-retirement` so the core
 * DecoyContactsEngine renders when Settings → Decoy Contacts is
 * tapped.
 */
private fun coreScreenIdToVariant(id: String): String? =
    when {
        // 1:1 screen-id ↔ AppScreen variant mappings.
        id == "contacts" -> "Contacts"

        id == "settings" -> "Settings"

        id == "device_management" -> "DeviceManagement"

        id == "groups" -> "Groups"

        id == "archived_contacts" -> "ArchivedContacts"

        id == "contact_duplicates" -> "ContactDuplicates"

        id == "device_replacement" -> "DeviceReplacement"

        id == "help" -> "Help"

        id == "more" -> "More"

        id == "decoy_contacts" -> "DecoyContacts"

        // Multi-state engines: each engine drives multiple `screen_id`s
        // (e.g. DuressPinEngine cycles `duress_overview` →
        // `duress_enter_pin` → `duress_confirm_pin` → `duress_alerts`)
        // but all sub-states render via the same `CoreScreenView` for
        // the parent `AppScreen` variant. Prefix-match so adding a new
        // sub-state in core doesn't silently fall through to the
        // legacy `Screen.Home` fallback (F2-NEW-4 was exactly that:
        // `duress_overview` and `backup_choose` had no entry, so taps
        // on the Settings rows landed on My Card instead of the
        // requested screen — Decoy Contacts worked because it has
        // only the single `decoy_contacts` id mapped above).
        id.startsWith("duress_") -> "DuressPin"

        id.startsWith("backup_") -> "Backup"

        id.startsWith("sync_") -> "Sync"

        else -> null
    }

/** Top-level core screen ids that show the bottom navigation bar. */
private val TOP_LEVEL_SCREEN_IDS =
    setOf(
        "my_info",
        "contacts",
        "exchange",
        "groups",
        "more",
    )

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
    val syncState by viewModel.syncState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    // selectedContactId removed — ContactDetail is now driven by core via
    // CoreAppViewModel.navigateToScreenWithParam("ContactDetail", "contact_id", …)
    // selectedLabelId removed — same rationale as selectedContactId:
    // GroupDetail navigation is driven by core via
    // `coreAppViewModel.navigateToScreenWithParam(...)`.
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

    // Deep link consent gate (SP-9). The state machine + URL parser
    // live in core (`PlatformAppEngine.handleDeepLinkUri`) since the
    // 2026-04-25-deeplink-consent-orchestrator cleanup. The native
    // dialog is shown whenever core's current screen is the consent
    // gate — `screenId == "deep_link_consent"`.
    val coreScreen by coreAppViewModel.screen.collectAsState()
    val showDeepLinkConsent = coreScreen?.screenId == "deep_link_consent"

    // Activity-enum-collapse Phase 1 dropped the `MultiStageExchange`
    // case; it now renders through the default `CoreScreenView` arm
    // driven directly by `coreScreen?.screenId`. The previous
    // transitional `LaunchedEffect` mirror that synced
    // `currentScreen` back into `Screen.ExchangeModePicker` when core
    // navigated away from `multi_stage_exchange` is no longer needed:
    // post-collapse, `currentScreen` is `Screen.ExchangeModePicker`
    // anyway when entering the multi-stage flow, and the default arm
    // follows core regardless of the enum value.

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
                "exchange" -> currentScreen = Screen.ExchangeModePicker

                "home" -> currentScreen = Screen.Home

                // Activity-enum collapse: Contacts and Settings render
                // through the default core-driven arm; navigate via core.
                "contacts" -> coreAppViewModel.navigateTo("Contacts")

                "settings" -> coreAppViewModel.navigateTo("Settings")
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

    // Phase 2b — observe core navigation into / out of the link-mode
    // responder screen and start / stop the
    // [LinkResponderSessionService] accordingly. After Accept on the
    // existing deep-link consent dialog, core navigates to
    // `link_responder_waiting`; this side-effect picks up the
    // transition automatically and attaches the listener to the
    // engine-cached session. The 0×0 placement is intentional — this
    // block is a side-effect carrier, not a UI element.
    val linkResponderService =
        remember(coreAppViewModel) { LinkResponderSessionService(coreAppViewModel) }
    LaunchedEffect(coreScreen?.screenId) {
        if (coreScreen?.screenId == "link_responder_waiting") {
            linkResponderService.startIfNeeded()
        } else {
            linkResponderService.stop()
        }
    }

    // Auto-sync when app comes to foreground
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
            coreAppViewModel.navigateTo("Contacts")
        }
    }

    // The bottom nav is shown only on top-level screens. The active
    // tab id is core's published `screenId` (when it's one of the
    // top-level set), since core owns the navigation state of record
    // post-collapse. Native screens still in the local enum (Home,
    // ExchangeModePicker, More, etc.) keep their selection by
    // delegating through `coreScreen?.screenId` after navigation has
    // landed on a core screen, OR by leaving `activeTabId` null when
    // currently on a non-top-level native screen — both behaviours
    // are unchanged from the pre-collapse mapping.
    val activeTabId = coreScreen?.screenId?.takeIf { it in TOP_LEVEL_SCREEN_IDS }
    val isTopLevel = activeTabId != null

    Scaffold(
        bottomBar = {
            if (isTopLevel && uiState is UiState.Ready && tabs.isNotEmpty()) {
                NavigationBar {
                    for (tab in tabs) {
                        // Route the tap through core's nav; the default
                        // `CoreScreenView` arm picks up the resulting
                        // `coreScreen.screenId` and renders.
                        val variant =
                            coreScreenIdToVariant(tab.id)
                                ?: tab.id.split('_').joinToString("") {
                                    it.replaceFirstChar(Char::uppercase)
                                }
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = imageVectorForCoreTab(tab.icon),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            selected = activeTabId == tab.id,
                            onClick = {
                                // Native top-level cases (my_info -> Home,
                                // exchange -> ExchangeModePicker, more -> More)
                                // still need the local enum until their
                                // per-pair retirement; everything else goes
                                // through core's nav.
                                when (tab.id) {
                                    "my_info" -> currentScreen = Screen.Home
                                    "exchange" -> currentScreen = Screen.ExchangeModePicker
                                    else -> coreAppViewModel.navigateTo(variant)
                                }
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
            // (ExchangeModePicker, hardware-aware MultiStageExchange,
            // NFC/BLE, Recovery, QrDiagnostic) and the pre-Ready boot
            // states (Home). Theme + language pickers retired Phase
            // 2a/A3a (`2026-05-01-android-humble-ui-deep-retirement`)
            // — they now live as `Component::Dropdown`s inside the
            // existing core-driven Settings screen.
            val coreVariant = coreScreen?.screenId?.let(::coreScreenIdToVariant)
            if (coreVariant != null && uiState is UiState.Ready) {
                CoreScreenView(
                    viewModel = coreAppViewModel,
                    screenName = coreVariant,
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
                                    onComplete = { displayName -> viewModel.onCoreOnboardingComplete(displayName) },
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
                                    onSettings = { coreAppViewModel.navigateTo("Settings") },
                                    syncState = syncState,
                                    isOnline = isOnline,
                                    lastSyncTime = lastSyncTime,
                                    onSync = { viewModel.sync() },
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

                    Screen.ExchangeModePicker -> {
                        ExchangeModePicker(
                            onModeSelected = { mode ->
                                when (mode) {
                                    ExchangeMode.QR -> {
                                        currentScreen = Screen.MultiStageExchange
                                    }

                                    ExchangeMode.NFC -> {
                                        currentScreen = Screen.NfcExchange
                                    }

                                    ExchangeMode.BLE -> {
                                        currentScreen = Screen.BleExchange
                                    }
                                }
                            },
                        )
                    }

                    Screen.MultiStageExchange -> {
                        MultiStageExchangeScreen(coreAppViewModel = coreAppViewModel)
                    }

                    Screen.NfcExchange -> {
                        NfcExchangeScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = Screen.ExchangeModePicker },
                            onDone = {
                                viewModel.refresh()
                                coreAppViewModel.navigateTo("Contacts")
                            },
                        )
                    }

                    Screen.BleExchange -> {
                        BleExchangeScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = Screen.ExchangeModePicker },
                            onDone = {
                                viewModel.refresh()
                                coreAppViewModel.navigateTo("Contacts")
                            },
                        )
                    }

                    Screen.Recovery -> {
                        RecoveryScreen(
                            coreAppViewModel = coreAppViewModel,
                            onBack = { coreAppViewModel.navigateTo("More") },
                        )
                    }

                    Screen.QrDiagnostic -> {
                        // Guard with BuildConfig.DEBUG so R8 can tree-shake the
                        // real QrDiagnosticScreen out of release APKs. In release,
                        // the no-op stub from src/release/ is compiled instead and
                        // the condition evaluates to a compile-time false.
                        if (BuildConfig.DEBUG) {
                            QrDiagnosticScreen(
                                onBack = { coreAppViewModel.navigateTo("Settings") },
                            )
                        } else {
                            coreAppViewModel.navigateTo("Settings")
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
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("Exchange Request")
        },
        text = {
            Text(
                "Someone shared an exchange link with you. " +
                    "Do you want to proceed with the contact exchange?\n\n" +
                    "Only accept if you trust the source of this link.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Accept Exchange")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Decline")
            }
        },
    )
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading...")
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
    syncState: SyncState = SyncState.Idle,
    isOnline: Boolean = true,
    lastSyncTime: Instant? = null,
    onSync: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vauchi") },
                actions = {
                    SyncStatusChip(
                        syncState = syncState,
                        isOnline = isOnline,
                        lastSyncTime = lastSyncTime,
                        onSync = onSync,
                        modifier = Modifier.testTag("home.sync"),
                    )
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("home.settings")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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

    LaunchedEffect(Unit) {
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock Vauchi")
                .setSubtitle("Enter your device PIN, pattern, or biometric")
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
                contentDescription = "Error",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text =
                    if (isLockScreenError) {
                        "Device Lock Required"
                    } else if (isCancelledError) {
                        "Authentication Required"
                    } else {
                        "Something went wrong"
                    },
                style = MaterialTheme.typography.headlineMedium,
                color = if (isCancelledError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    if (isLockScreenError) {
                        "A device lock screen is required to use Vauchi. " +
                            "PIN, pattern, fingerprint, or face unlock all qualify."
                    } else if (isCancelledError) {
                        "Vauchi needs to verify your identity to unlock your encrypted contacts."
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
                    text = "Vauchi encrypts your contacts — device authentication is required to access them.",
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
                    Text("Open Settings")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().testTag("error.retry"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry")
                }
            } else {
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry, modifier = Modifier.testTag("error.retry")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
fun OfflineBanner() {
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
            contentDescription = "Offline",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "You're offline",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
fun SyncStatusChip(
    syncState: SyncState,
    isOnline: Boolean,
    lastSyncTime: Instant?,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when {
            !isOnline -> {
                "Offline" to MaterialTheme.colorScheme.outline
            }

            syncState is SyncState.Syncing -> {
                "Syncing..." to MaterialTheme.colorScheme.primary
            }

            syncState is SyncState.Error -> {
                "Sync failed" to MaterialTheme.colorScheme.error
            }

            syncState is SyncState.Success || lastSyncTime != null -> {
                val timeText =
                    lastSyncTime?.let {
                        val formatter =
                            DateTimeFormatter
                                .ofPattern("HH:mm")
                                .withZone(ZoneId.systemDefault())
                        formatter.format(it)
                    } ?: ""
                "Synced $timeText" to MaterialTheme.colorScheme.primary
            }

            else -> {
                "Tap to sync" to MaterialTheme.colorScheme.outline
            }
        }

    TextButton(
        onClick = { if (isOnline && syncState !is SyncState.Syncing) onSync() },
        enabled = isOnline && syncState !is SyncState.Syncing,
        modifier = modifier,
    ) {
        if (syncState is SyncState.Syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// Restore Identity Dialog
@Composable
fun RestoreIdentityDialog(
    onDismiss: () -> Unit,
    onRestore: (backupData: String, password: String) -> Unit,
) {
    var backupData by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRestoring by remember { mutableStateOf(false) }

    val canRestore = backupData.isNotBlank() && password.isNotEmpty()

    AlertDialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        title = { Text("Restore Identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Enter your backup data and password to restore your identity. This will replace any existing identity on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = backupData,
                    onValueChange = { backupData = it },
                    label = { Text("Backup Data") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    enabled = !isRestoring,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
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
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRestoring,
            ) {
                Text("Cancel")
            }
        },
    )
}
