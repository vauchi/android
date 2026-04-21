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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import app.vauchi.ui.ContactMergeScreen
import app.vauchi.ui.DevicesScreen
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
import app.vauchi.ui.model.ContentApplyResult
import app.vauchi.ui.model.ContentUpdateStatus
import app.vauchi.ui.model.ContentUpdateType
import app.vauchi.ui.model.PasswordStrengthResult
import app.vauchi.ui.theme.VauchiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContentType
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileUpdateStatus
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

    // Handle OpenUrl events from core-driven screens
    val openUrlEvent by coreAppViewModel.openUrlEvent.collectAsState()
    LaunchedEffect(openUrlEvent) {
        openUrlEvent?.let { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            coreAppViewModel.consumeOpenUrlEvent()
        }
    }

    // Deep link consent gate (SP-9)
    val deepLinkHandler = remember { app.vauchi.deeplink.DeepLinkHandler() }
    var showDeepLinkConsent by remember { mutableStateOf(false) }
    var deepLinkPayload by remember { mutableStateOf<String?>(null) }

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

    // Handle incoming deep link URI with consent gate
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            val result = deepLinkHandler.handleDeepLink(uri)
            when (result) {
                is app.vauchi.deeplink.DeepLinkResult.ExchangePending -> {
                    deepLinkPayload = result.exchangePayload
                    showDeepLinkConsent = true
                }

                is app.vauchi.deeplink.DeepLinkResult.Invalid -> {
                    snackbarHostState.showSnackbar("Invalid link: ${result.reason}")
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

    // Top-level screens that show the bottom navigation bar
    val isTopLevel =
        currentScreen in
            setOf(
                Screen.Home,
                Screen.Contacts,
                Screen.ExchangeModePicker,
                Screen.Labels,
                Screen.More,
            )

    Scaffold(
        bottomBar = {
            if (isTopLevel && uiState is UiState.Ready) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Person, contentDescription = "My Card") },
                        label = { Text("My Card") },
                        selected = currentScreen == Screen.Home,
                        onClick = { currentScreen = Screen.Home },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.People, contentDescription = "Contacts") },
                        label = { Text("Contacts") },
                        selected = currentScreen == Screen.Contacts,
                        onClick = { currentScreen = Screen.Contacts },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.QrCode, contentDescription = "Exchange") },
                        label = { Text("Exchange") },
                        selected =
                            currentScreen in
                                setOf(Screen.ExchangeModePicker, Screen.MultiStageExchange, Screen.NfcExchange, Screen.BleExchange),
                        onClick = { currentScreen = Screen.ExchangeModePicker },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
                        label = { Text("Groups") },
                        selected = currentScreen == Screen.Labels,
                        onClick = { currentScreen = Screen.Labels },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "More") },
                        label = { Text("More") },
                        selected = currentScreen == Screen.More,
                        onClick = { currentScreen = Screen.More },
                    )
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
                                displayName = state.displayName,
                                publicId = state.publicId,
                                card = state.card,
                                contactCount = state.contactCount,
                                onAddField = viewModel::addField,
                                onRemoveField = viewModel::removeField,
                                onExchange = { currentScreen = Screen.MultiStageExchange },
                                onContacts = { currentScreen = Screen.Contacts },
                                onSettings = { currentScreen = Screen.Settings },
                                socialNetworks = viewModel.listSocialNetworks(),
                                onGetProfileUrl = viewModel::getProfileUrl,
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
                    DevicesScreen(
                        onBack = { currentScreen = Screen.More },
                        viewModel = viewModel,
                        getDevices = { viewModel.getDevices() },
                        generateLinkQr = { viewModel.generateDeviceLinkQr() },
                        unlinkDevice = { index -> viewModel.unlinkDevice(index) },
                        isPrimaryDevice = { viewModel.isPrimaryDevice() },
                    )
                }

                Screen.Recovery -> {
                    RecoveryScreen(
                        viewModel = viewModel,
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
                    ContactMergeScreen(
                        onBack = { currentScreen = Screen.More },
                        onFindDuplicates = { viewModel.findDuplicates() },
                        onGetContact = { id -> viewModel.getContact(id) },
                        onMergeContacts = { primaryId, secondaryId ->
                            viewModel.mergeContacts(primaryId, secondaryId)
                        },
                        onDismissDuplicate = { id1, id2 ->
                            viewModel.dismissDuplicate(id1, id2)
                        },
                        onSoftDeleteImported = { id ->
                            viewModel.softDeleteImportedContact(id)
                        },
                        onShowMessage = { viewModel.showMessage(it) },
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

    // Deep link consent dialog (SP-9)
    // NEVER auto-process — always ask the user first
    if (showDeepLinkConsent && deepLinkPayload != null) {
        DeepLinkConsentDialog(
            onConfirm = {
                showDeepLinkConsent = false
                deepLinkHandler.grantConsent()
                // TODO: Deep links use the old wb:// single-QR format.
                // Navigate to multi-stage exchange for now; deep link exchange
                // will be re-implemented when the protocol supports it.
                currentScreen = Screen.MultiStageExchange
                deepLinkPayload = null
            },
            onDeny = {
                showDeepLinkConsent = false
                deepLinkHandler.denyConsent()
                deepLinkPayload = null
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyScreen(
    displayName: String,
    publicId: String,
    card: MobileContactCard,
    contactCount: UInt,
    onAddField: (MobileFieldType, String, String) -> Unit,
    onRemoveField: (String) -> Unit,
    onExchange: () -> Unit,
    onContacts: () -> Unit,
    onSettings: () -> Unit,
    socialNetworks: List<uniffi.vauchi_platform.MobileSocialNetwork> = emptyList(),
    onGetProfileUrl: (String, String) -> String? = { _, _ -> null },
    syncState: SyncState = SyncState.Idle,
    isOnline: Boolean = true,
    lastSyncTime: Instant? = null,
    onSync: () -> Unit = {},
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vauchi") },
                actions = {
                    // Sync status indicator
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
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.testTag("home.add_field")) {
                Icon(Icons.Default.Add, contentDescription = "Add field")
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Offline banner
            if (!isOnline) {
                OfflineBanner()
            }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 24.dp),
            ) {
                item {
                    Text(
                        text = "Hello, $displayName!",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Your Card",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Public ID: ${publicId.take(16)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "Fields",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (card.fields.isEmpty()) {
                    item {
                        Text(
                            text = "No fields yet. Tap + to add contact info!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(card.fields) { field ->
                        val context = LocalContext.current
                        val isSocialField = field.fieldType == MobileFieldType.SOCIAL
                        val profileUrl =
                            if (isSocialField) {
                                onGetProfileUrl(field.label, field.value)
                            } else {
                                null
                            }

                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (profileUrl != null) {
                                            Modifier.clickable {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl))
                                                context.startActivity(intent)
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = field.label,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        text = if (isSocialField) "@${field.value}" else field.value,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color =
                                            if (profileUrl !=
                                                null
                                            ) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                }
                                if (profileUrl != null) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Open profile",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = { onRemoveField(field.label) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Contacts: $contactCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Button(
                            onClick = onExchange,
                            modifier = Modifier.weight(1f).testTag("home.exchange"),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Exchange")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exchange")
                        }
                        OutlinedButton(
                            onClick = onContacts,
                            modifier = Modifier.weight(1f).testTag("home.contacts"),
                        ) {
                            Icon(Icons.Default.Person, contentDescription = "Contacts")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Contacts")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFieldDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { type, label, value ->
                onAddField(type, label, value)
                showAddDialog = false
            },
            socialNetworks = socialNetworks,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFieldDialog(
    onDismiss: () -> Unit,
    onAdd: (MobileFieldType, String, String) -> Unit,
    socialNetworks: List<uniffi.vauchi_platform.MobileSocialNetwork> = emptyList(),
) {
    var selectedType by remember { mutableStateOf(MobileFieldType.EMAIL) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var socialExpanded by remember { mutableStateOf(false) }
    var selectedNetwork by remember { mutableStateOf<uniffi.vauchi_platform.MobileSocialNetwork?>(null) }
    var socialSearch by remember { mutableStateOf("") }

    val fieldTypes =
        listOf(
            MobileFieldType.EMAIL to "Email",
            MobileFieldType.PHONE to "Phone",
            MobileFieldType.WEBSITE to "Website",
            MobileFieldType.ADDRESS to "Address",
            MobileFieldType.SOCIAL to "Social",
            MobileFieldType.CUSTOM to "Custom",
        )

    // Filter social networks by search
    val filteredNetworks =
        remember(socialSearch, socialNetworks) {
            if (socialSearch.isBlank()) {
                socialNetworks.take(10)
            } else {
                socialNetworks
                    .filter {
                        it.displayName.contains(socialSearch, ignoreCase = true)
                    }.take(10)
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Field type dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = fieldTypes.find { it.first == selectedType }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier =
                            Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        fieldTypes.forEach { (type, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedType = type
                                    if (type != MobileFieldType.SOCIAL && label.isEmpty()) {
                                        label = name
                                    }
                                    if (type == MobileFieldType.SOCIAL) {
                                        label = ""
                                        selectedNetwork = null
                                    }
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                // Social network picker (only shown for SOCIAL type)
                if (selectedType == MobileFieldType.SOCIAL) {
                    ExposedDropdownMenuBox(
                        expanded = socialExpanded,
                        onExpandedChange = { socialExpanded = !socialExpanded },
                    ) {
                        OutlinedTextField(
                            value = selectedNetwork?.displayName ?: socialSearch,
                            onValueChange = {
                                socialSearch = it
                                selectedNetwork = null
                                socialExpanded = true
                            },
                            label = { Text("Social Network") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = socialExpanded) },
                            modifier =
                                Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                                    .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = socialExpanded,
                            onDismissRequest = { socialExpanded = false },
                        ) {
                            filteredNetworks.forEach { network ->
                                DropdownMenuItem(
                                    text = { Text(network.displayName) },
                                    onClick = {
                                        selectedNetwork = network
                                        label = network.displayName
                                        socialSearch = network.displayName
                                        socialExpanded = false
                                    },
                                )
                            }
                            if (filteredNetworks.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No networks found") },
                                    onClick = { },
                                    enabled = false,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Regular label and value fields
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selectedType, label, value) },
                enabled = label.isNotBlank() && value.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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

// Content Updates mapping functions
private fun mapMobileUpdateStatus(status: MobileUpdateStatus): ContentUpdateStatus =
    when (status) {
        is MobileUpdateStatus.UpToDate -> {
            ContentUpdateStatus.UpToDate
        }

        is MobileUpdateStatus.UpdatesAvailable -> {
            ContentUpdateStatus.UpdatesAvailable(
                status.types.map { mapMobileContentType(it) },
            )
        }

        is MobileUpdateStatus.CheckFailed -> {
            ContentUpdateStatus.CheckFailed(status.error)
        }

        is MobileUpdateStatus.Disabled -> {
            ContentUpdateStatus.Disabled
        }
    }

private fun mapMobileApplyResult(result: MobileApplyResult): ContentApplyResult =
    when (result) {
        is MobileApplyResult.NoUpdates -> {
            ContentApplyResult.NoUpdates
        }

        is MobileApplyResult.Applied -> {
            ContentApplyResult.Applied(
                applied = result.applied.map { mapMobileContentType(it) },
                failed = result.failed.map { mapMobileContentType(it.contentType) },
            )
        }

        is MobileApplyResult.Disabled -> {
            ContentApplyResult.Disabled
        }

        is MobileApplyResult.Error -> {
            ContentApplyResult.Error(result.error)
        }
    }

private fun mapMobileContentType(type: MobileContentType): ContentUpdateType =
    when (type) {
        MobileContentType.NETWORKS -> ContentUpdateType.Networks
        MobileContentType.LOCALES -> ContentUpdateType.Locales
        MobileContentType.THEMES -> ContentUpdateType.Themes
        MobileContentType.HELP -> ContentUpdateType.Help
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
