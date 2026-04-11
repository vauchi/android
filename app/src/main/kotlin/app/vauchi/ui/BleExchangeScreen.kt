// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.vauchi.exchange.ExchangeDispatcher
import app.vauchi.exchange.ExchangeDispatcherFactory
import app.vauchi.ui.components.PermissionRationaleDialog
import app.vauchi.ui.components.rememberPermissionState
import app.vauchi.util.LocalizationManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.MobileExchangeSession
import uniffi.vauchi_platform.MobileExchangeState
import java.util.concurrent.atomic.AtomicBoolean

/** QR code colors: gray reduces screen glare at close face-to-face distance. */
private val BLE_QR_FOREGROUND = android.graphics.Color.rgb(64, 64, 64)
private val BLE_QR_BACKGROUND = android.graphics.Color.rgb(224, 224, 224)

private val BleBackground = Color(0xFFF0F5FF)
private val BleQrPlaceholderBackground = Color(0xFFE0E0E0)

/**
 * BLE exchange screen. Drives the QR-bootstrap BLE exchange protocol.
 *
 * Flow:
 * 1. Generate our QR via [MobileExchangeSession.generateQr] and display it.
 * 2. User scans peer QR — feed it via [MobileExchangeSession.processQr].
 * 3. [ExchangeCommandHandler.drainAndDispatch] dispatches BLE commands automatically.
 * 4. Poll [MobileExchangeSession.state] until [MobileExchangeState.Complete] or [MobileExchangeState.Failed].
 * 5. On [MobileExchangeState.Complete], finalize the exchange via the repository.
 *
 * @param viewModel The main view model (for repository access).
 * @param onBack Navigate back to the exchange mode picker.
 * @param onDone Navigate to contacts after success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleExchangeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // Lock orientation to portrait during exchange
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Keep screen on during exchange
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousBrightness = activity?.window?.attributes?.screenBrightness ?: -1.0f
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            params.screenBrightness = 0.65f
            window.attributes = params
        }
        onDispose {
            activity?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val params = window.attributes
                params.screenBrightness = previousBrightness
                window.attributes = params
            }
        }
    }

    var session by remember { mutableStateOf<MobileExchangeSession?>(null) }
    var commandHandler by remember { mutableStateOf<ExchangeDispatcher?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var exchangeState by remember { mutableStateOf<MobileExchangeState>(MobileExchangeState.Idle) }
    var contactName by remember { mutableStateOf<String?>(null) }
    var finalizationError by remember { mutableStateOf<String?>(null) }
    var useFrontCamera by remember { mutableStateOf(false) }
    val scannerGuard = remember { AtomicBoolean(false) }

    val cameraPermState =
        rememberPermissionState(
            permission = Manifest.permission.CAMERA,
            title = localizationManager.t("exchange.camera_permission_title"),
            rationale = localizationManager.t("exchange.camera_needed_description"),
        )
    val cameraPermissionGranted = cameraPermState.isGranted

    LaunchedEffect(cameraPermissionGranted) { if (!cameraPermissionGranted) cameraPermState.request() }
    // Report camera permission denial to core when handler is ready and camera
    // is still denied. Both keys are reactive — re-evaluates when permission
    // state changes or when the command handler becomes available.
    var cameraPermDeniedReported by remember { mutableStateOf(false) }
    LaunchedEffect(commandHandler, cameraPermissionGranted) {
        val handler = commandHandler ?: return@LaunchedEffect
        if (!cameraPermissionGranted && !cameraPermDeniedReported) {
            cameraPermDeniedReported = true
            handler.reportPermissionDenied("camera")
        }
    }
    PermissionRationaleDialog(cameraPermState)

    // Initialize session on screen entry
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val sessionData = viewModel.generateBleExchangeSession()
                val handler = ExchangeDispatcherFactory.create(sessionData.session, context)
                withContext(Dispatchers.Main) {
                    session = sessionData.session
                    commandHandler = handler
                    qrBitmap = generateBleBitmap(sessionData.exchangeData.qrData)
                    exchangeState = sessionData.session.state()
                }
                handler.drainAndDispatch()
            } catch (e: Exception) {
                Log.e("Vauchi", "BLE exchange: session init failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    exchangeState = MobileExchangeState.Failed("Failed to start exchange session")
                }
            }
        }
    }

    // State polling loop — drives BLE exchange progress after QR bootstrap
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            delay(500L)
            val state = s.state()
            exchangeState = state
            if (state is MobileExchangeState.Complete || state is MobileExchangeState.Failed) break
        }
    }

    // Finalize on Complete
    LaunchedEffect(exchangeState) {
        val state = exchangeState
        if (state is MobileExchangeState.Complete && contactName == null && finalizationError == null) {
            val s = session ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                try {
                    val result = viewModel.finalizeBleExchange(s)
                    withContext(Dispatchers.Main) {
                        contactName = result?.contactName ?: state.contactName
                        viewModel.refresh()
                        Log.i("Vauchi", "BLE exchange: contact saved")
                    }
                } catch (e: Exception) {
                    Log.e("Vauchi", "BLE exchange: finalization failed: ${e.javaClass.simpleName}")
                    withContext(Dispatchers.Main) {
                        finalizationError = "Failed to save contact"
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(BleBackground)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp).testTag("ble_exchange.back"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = localizationManager.t("action.back"),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    localizationManager.t("exchange.ble_title"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(BleBackground),
        ) {
            when {
                exchangeState is MobileExchangeState.Complete || contactName != null || finalizationError != null -> {
                    BleExchangeSuccessOverlay(
                        contactName = contactName,
                        finalizationError = finalizationError,
                        localizationManager = localizationManager,
                        onDone = onDone,
                    )
                }

                exchangeState is MobileExchangeState.Failed -> {
                    val failedState = exchangeState as MobileExchangeState.Failed
                    BleExchangeFailedOverlay(
                        error = failedState.error,
                        localizationManager = localizationManager,
                        onRetry = {
                            // Reset state and re-initialize
                            session = null
                            commandHandler = null
                            qrBitmap = null
                            contactName = null
                            finalizationError = null
                            exchangeState = MobileExchangeState.Idle
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val sessionData = viewModel.generateBleExchangeSession()
                                        val handler = ExchangeDispatcherFactory.create(sessionData.session, context)
                                        withContext(Dispatchers.Main) {
                                            session = sessionData.session
                                            commandHandler = handler
                                            qrBitmap = generateBleBitmap(sessionData.exchangeData.qrData)
                                            exchangeState = sessionData.session.state()
                                        }
                                        handler.drainAndDispatch()
                                    } catch (e: Exception) {
                                        Log.e("Vauchi", "BLE exchange: retry init failed: ${e.javaClass.simpleName}")
                                        withContext(Dispatchers.Main) {
                                            exchangeState = MobileExchangeState.Failed("Failed to restart exchange session")
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                else -> {
                    BleExchangeActiveContent(
                        qrBitmap = qrBitmap,
                        exchangeState = exchangeState,
                        cameraPermissionGranted = cameraPermissionGranted,
                        useFrontCamera = useFrontCamera,
                        localizationManager = localizationManager,
                        onQrScanned = { peerQrData ->
                            if (!scannerGuard.getAndSet(true)) {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            session?.processQr(peerQrData)
                                            commandHandler?.drainAndDispatch()
                                        } catch (e: Exception) {
                                            Log.e("Vauchi", "BLE exchange: processQr failed: ${e.javaClass.simpleName}")
                                        }
                                    }
                                    delay(50L)
                                    scannerGuard.set(false)
                                }
                            }
                        },
                        onRequestPermission = { cameraPermState.request() },
                        onCameraSwitch = { useFrontCamera = !useFrontCamera },
                    )
                }
            }
        }
    }
}

@Composable
private fun BleExchangeActiveContent(
    qrBitmap: Bitmap?,
    exchangeState: MobileExchangeState,
    cameraPermissionGranted: Boolean,
    useFrontCamera: Boolean,
    localizationManager: LocalizationManager,
    onQrScanned: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onCameraSwitch: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Our QR code for the peer to scan
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = localizationManager.t("exchange.qr_code"),
                modifier =
                    Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .background(BleQrPlaceholderBackground, shape = RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("ble_exchange.qr_code"),
            )
        } ?: Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .background(BleQrPlaceholderBackground, shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        Spacer(modifier = Modifier.height(16.dp))

        BleStateIndicator(exchangeState, localizationManager)

        Spacer(modifier = Modifier.weight(1f))

        // Camera scanner to scan the peer's QR
        if (cameraPermissionGranted) {
            Text(
                text = localizationManager.t("exchange.point_camera"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF555555),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("ble_exchange.camera"),
            ) {
                key(useFrontCamera) {
                    FaceToFaceCameraPreview(
                        useFrontCamera = useFrontCamera,
                        showPreview = true,
                        onQrCodeDetected = onQrScanned,
                    )
                }
            }
            TextButton(
                onClick = onCameraSwitch,
                modifier = Modifier.testTag("ble_exchange.camera_switch"),
            ) {
                Text(
                    if (useFrontCamera) {
                        localizationManager.t("exchange.switch_rear_camera")
                    } else {
                        localizationManager.t("exchange.switch_front_camera")
                    },
                )
            }
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.testTag("ble_exchange.grant_permission"),
            ) {
                Text(localizationManager.t("exchange.grant_permission"))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BleStateIndicator(
    state: MobileExchangeState,
    localizationManager: LocalizationManager,
) {
    val label =
        when (state) {
            is MobileExchangeState.Idle -> localizationManager.t("exchange.waiting_peer")
            is MobileExchangeState.DisplayingQr -> localizationManager.t("exchange.waiting_peer")
            is MobileExchangeState.PeerScanned -> localizationManager.t("exchange.peer_found")
            is MobileExchangeState.AwaitingKeyAgreement -> localizationManager.t("exchange.verifying")
            is MobileExchangeState.AwaitingCardExchange -> localizationManager.t("exchange.transferring")
            is MobileExchangeState.Complete -> localizationManager.t("exchange.contact_exchanged")
            is MobileExchangeState.Failed -> localizationManager.t("exchange.failed_title")
        }
    val showProgress =
        state is MobileExchangeState.Idle ||
            state is MobileExchangeState.DisplayingQr ||
            state is MobileExchangeState.PeerScanned ||
            state is MobileExchangeState.AwaitingKeyAgreement ||
            state is MobileExchangeState.AwaitingCardExchange

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF444444),
            modifier = Modifier.testTag("ble_exchange.state_label"),
        )
    }
}

@Composable
private fun BleExchangeSuccessOverlay(
    contactName: String?,
    finalizationError: String?,
    localizationManager: LocalizationManager,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(BleBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (finalizationError != null) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = localizationManager.t("status.error"),
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    localizationManager.t("exchange.save_failed"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    finalizationError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = localizationManager.t("status.success"),
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    localizationManager.t("exchange.contact_exchanged"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (contactName != null) {
                        localizationManager.t("exchange.contact_added", mapOf("name" to contactName))
                    } else {
                        localizationManager.t("exchange.new_contact_added")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDone,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag("ble_exchange.done"),
            ) {
                Text(localizationManager.t("action.done"))
            }
        }
    }
}

@Composable
private fun BleExchangeFailedOverlay(
    error: String,
    localizationManager: LocalizationManager,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(BleBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = localizationManager.t("status.failed"),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                localizationManager.t("exchange.failed_title"),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag("ble_exchange.retry"),
            ) {
                Text(localizationManager.t("action.retry"))
            }
        }
    }
}

/** Generate a QR bitmap from [data] using high error correction for BLE bootstrap. */
private fun generateBleBitmap(data: String): Bitmap {
    val writer = QRCodeWriter()
    val hints =
        mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
            com.google.zxing.EncodeHintType.MARGIN to 3,
        )
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 800, 800, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) BLE_QR_FOREGROUND else BLE_QR_BACKGROUND)
        }
    }
    return bitmap
}
