// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import app.vauchi.ui.AppPasswordScreen
import app.vauchi.ui.BleExchangeScreen
import app.vauchi.ui.ContactDetailScreen
import app.vauchi.ui.ExchangeMode
import app.vauchi.ui.ExchangeModePicker
import app.vauchi.ui.HelpScreen
import app.vauchi.ui.LanguageSettingsScreen
import app.vauchi.ui.MainViewModel
import app.vauchi.ui.MoreScreen
import app.vauchi.ui.MultiStageExchangeScreen
import app.vauchi.ui.NfcExchangeScreen
import app.vauchi.ui.QrDiagnosticScreen
import app.vauchi.ui.RecoveryScreen
import app.vauchi.ui.SyncState
import app.vauchi.ui.ThemeSettingsScreen
import app.vauchi.ui.UiState
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreOnboardingScreen
import app.vauchi.ui.coreui.CoreScreenView
import app.vauchi.ui.coreui.MaterialIconName
import app.vauchi.ui.coreui.UserAction
import app.vauchi.ui.coreui.coreTabIdForScreen
import app.vauchi.ui.coreui.materialIconNameForCoreIcon
import app.vauchi.ui.coreui.screenForCoreTabId
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
    Home,
    ExchangeModePicker,
    MultiStageExchange,
    NfcExchange,
    BleExchange,
    Contacts,
    ContactDetail,
    Settings,
    Devices,
    Recovery,
    Labels,
    LabelDetail,
    ThemeSettings,
    LanguageSettings,
    Help,
    QrDiagnostic,
    More,
    ArchivedContacts,
    ContactMerge,
    DeviceReplacement,
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
    val syncState by viewModel.syncState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var selectedLabelId by remember { mutableStateOf<String?>(null) }
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

    // Deep link consent gate (SP-9). The state machine + URL parser
    // live in core (`PlatformAppEngine.handleDeepLinkUri`) since the
    // 2026-04-25-deeplink-consent-orchestrator cleanup. The native
    // dialog is shown whenever core's current screen is the consent
    // gate — `screenId == "deep_link_consent"`.
    val coreScreen by coreAppViewModel.screen.collectAsState()
    val showDeepLinkConsent = coreScreen?.screenId == "deep_link_consent"

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
                "contacts" -> currentScreen = Screen.Contacts
                "settings" -> currentScreen = Screen.Settings
                "home" -> currentScreen = Screen.Home
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

    // Dynamic default screen: land on Contacts if user has contacts
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is UiState.Ready && currentScreen == Screen.Home && state.contactCount > 0u) {
            currentScreen = Screen.Contacts
        }
    }

    // The bottom nav is shown only on top-level screens. "Top-level"
    // is now whatever core's `tab_info(locale)` returned — the local
    // hardcoded set is gone (§6 pure-renderer remediation).
    val activeTabId = coreTabIdForScreen(currentScreen)
    val isTopLevel = activeTabId != null

    Scaffold(
        bottomBar = {
            if (isTopLevel && uiState is UiState.Ready && tabs.isNotEmpty()) {
                NavigationBar {
                    for (tab in tabs) {
                        val targetScreen = screenForCoreTabId(tab.id) ?: continue
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = imageVectorForCoreTab(tab.icon),
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            selected = activeTabId == tab.id,
                            onClick = { currentScreen = targetScreen },
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
                                onSettings = { currentScreen = Screen.Settings },
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
                    MultiStageExchangeScreen(
                        viewModel = viewModel,
                        onBack = {
                            viewModel.cancelMultiStageExchange()
                            currentScreen = Screen.ExchangeModePicker
                        },
                        onDone = {
                            viewModel.cancelMultiStageExchange()
                            viewModel.refresh()
                            currentScreen = Screen.Contacts
                        },
                    )
                }

                Screen.NfcExchange -> {
                    NfcExchangeScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = Screen.ExchangeModePicker },
                        onDone = {
                            viewModel.refresh()
                            currentScreen = Screen.Contacts
                        },
                    )
                }

                Screen.BleExchange -> {
                    BleExchangeScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = Screen.ExchangeModePicker },
                        onDone = {
                            viewModel.refresh()
                            currentScreen = Screen.Contacts
                        },
                    )
                }

                Screen.Contacts -> {
                    // Phase 1A.2 / 1B.2 (core-gui-architecture-alignment):
                    // the Contacts tab is now a thin Compose shell around
                    // `CoreScreenView("Contacts")`. Core's ContactListEngine
                    // owns search, row actions (archive/hide/delete via the
                    // ListItemAction overflow menu wired in MR !304), the
                    // "Archived Contacts" and "Find Duplicates" screen
                    // actions (AppEngine intercepts + navigates), and the
                    // empty-state InfoPanel. See
                    // `core/vauchi-app/src/ui/contact_list.rs`.
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "Contacts",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.ContactDetail -> {
                    selectedContactId?.let { contactId ->
                        ContactDetailScreen(
                            contactId = contactId,
                            onBack = { currentScreen = Screen.Contacts },
                            onGetContact = { viewModel.getContact(it) },
                            onGetOwnCard = { viewModel.getOwnCard() },
                            onSetFieldVisibility = { cId, label, visible ->
                                viewModel.setFieldVisibility(cId, label, visible)
                            },
                            onIsFieldVisible = { cId, label ->
                                viewModel.isFieldVisibleToContact(cId, label)
                            },
                            onVerifyContact = { viewModel.verifyContact(it) },
                            onGetOwnPublicKey = { viewModel.getOwnPublicKey() },
                            onGetOwnFingerprint = { viewModel.getOwnFingerprint() },
                            onTrustForRecovery = { viewModel.trustContactForRecovery(it) },
                            onUntrustForRecovery = { viewModel.untrustContactForRecovery(it) },
                            onGetContactNote = { viewModel.getContactNote(it) },
                            onSetContactNote = { cId, note -> viewModel.setContactNote(cId, note) },
                            onGetContactFieldNotes = { viewModel.getContactFieldNotes(it) },
                            onSetContactFieldNote = { cId, fId, note -> viewModel.setContactFieldNote(cId, fId, note) },
                            onDeleteContactFieldNote = { cId, fId -> viewModel.deleteContactFieldNote(cId, fId) },
                            onSetProposalTrusted = { cId, trusted -> viewModel.setProposalTrusted(cId, trusted) },
                            onArchiveContact = { id -> viewModel.archiveContact(id) },
                            onUnarchiveContact = { id -> viewModel.unarchiveContact(id) },
                            onSoftDeleteContact = { id -> viewModel.softDeleteImportedContact(id) },
                            onUndoSoftDeleteContact = { id -> viewModel.undoDeleteImportedContact(id) },
                            onGetFooterActionId = { id -> viewModel.contactDetailFooterActionId(id) },
                        )
                    }
                }

                Screen.Settings -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "Settings",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.Devices -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "DeviceManagement",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.Recovery -> {
                    RecoveryScreen(
                        coreAppViewModel = coreAppViewModel,
                        onBack = { currentScreen = Screen.More },
                    )
                }

                Screen.Labels -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "Groups",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.LabelDetail -> {
                    selectedLabelId?.let { labelId ->
                        CoreScreenView(
                            viewModel = coreAppViewModel,
                            screenName = "GroupDetail",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Screen.ThemeSettings -> {
                    ThemeSettingsScreen(
                        onBack = { currentScreen = Screen.Settings },
                    )
                }

                Screen.LanguageSettings -> {
                    LanguageSettingsScreen(
                        onBack = { currentScreen = Screen.Settings },
                    )
                }

                Screen.Help -> {
                    HelpScreen(
                        viewModel = coreAppViewModel,
                    )
                }

                Screen.QrDiagnostic -> {
                    // Guard with BuildConfig.DEBUG so R8 can tree-shake the
                    // real QrDiagnosticScreen out of release APKs. In release,
                    // the no-op stub from src/release/ is compiled instead and
                    // the condition evaluates to a compile-time false.
                    if (BuildConfig.DEBUG) {
                        QrDiagnosticScreen(
                            onBack = { currentScreen = Screen.Settings },
                        )
                    } else {
                        currentScreen = Screen.Settings
                    }
                }

                Screen.More -> {
                    MoreScreen(
                        onSettings = { currentScreen = Screen.Settings },
                        onHelp = { currentScreen = Screen.Help },
                        onDevices = { currentScreen = Screen.Devices },
                        onRecovery = { currentScreen = Screen.Recovery },
                        onArchivedContacts = { currentScreen = Screen.ArchivedContacts },
                        onMergeContacts = { currentScreen = Screen.ContactMerge },
                        onDeviceReplacement = { currentScreen = Screen.DeviceReplacement },
                    )
                }

                Screen.ArchivedContacts -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "ArchivedContacts",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.ContactMerge -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "ContactDuplicates",
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Screen.DeviceReplacement -> {
                    CoreScreenView(
                        viewModel = coreAppViewModel,
                        screenName = "DeviceReplacement",
                        modifier = Modifier.fillMaxSize(),
                    )
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
