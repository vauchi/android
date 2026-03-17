// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.ui.components.PermissionRationaleDialog
import app.vauchi.ui.components.rememberPermissionState
import app.vauchi.util.LocalizationManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.vauchi_platform.MobileProtocolState
import uniffi.vauchi_platform.MobileQrPayload
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** QR code colors: gray reduces screen glare at close face-to-face distance. */
private val QR_FOREGROUND = android.graphics.Color.rgb(64, 64, 64) // #404040
private val QR_BACKGROUND = android.graphics.Color.rgb(224, 224, 224) // #E0E0E0

/** Warm beige background: soft, non-reflective. */
private val ExchangeBackground = Color(0xFFF5F0EB)

private enum class ScanQuality {
    GOOD, // Green — QR being scanned right now
    FAIR, // Orange — scanned recently but stale
    NONE, // Red — no scan detected
}

/**
 * Multi-stage exchange screen: displays cycling QR codes driven by the core
 * and scans peer QR codes continuously. The core handles all protocol logic —
 * this screen is a pure display shell.
 *
 * QR payloads cycle rapidly (every ~300ms during DATA stage) to transfer
 * chunked card data between devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiStageExchangeScreen(
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

    // Force max screen brightness during exchange
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousBrightness = activity?.window?.attributes?.screenBrightness ?: -1.0f
        activity?.window?.let { window ->
            val params = window.attributes
            params.screenBrightness = 1.0f
            window.attributes = params
        }
        onDispose {
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = previousBrightness
                window.attributes = params
            }
        }
    }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var useFrontCamera by remember { mutableStateOf(true) }
    val scannerGuard = remember { AtomicBoolean(false) }
    val multiStageState by viewModel.multiStageState.collectAsState()
    var graceCompleted by remember { mutableStateOf(false) }
    var finalizationResult by remember { mutableStateOf<String?>(null) }
    var finalizationError by remember { mutableStateOf<String?>(null) }
    var lastScanTimestamp by remember { mutableLongStateOf(0L) }
    var scanTick by remember { mutableLongStateOf(0L) }

    // Scan quality: green = recent scan (<500ms), orange = stale (<2s), red = no scan
    val scanQuality by remember {
        derivedStateOf {
            // Read scanTick to force recomposition on tick updates
            @Suppress("UNUSED_EXPRESSION")
            scanTick
            val elapsed = System.currentTimeMillis() - lastScanTimestamp
            when {
                lastScanTimestamp == 0L -> ScanQuality.NONE
                elapsed < 500 -> ScanQuality.GOOD
                elapsed < 2000 -> ScanQuality.FAIR
                else -> ScanQuality.NONE
            }
        }
    }

    val cameraPermState =
        rememberPermissionState(
            permission = Manifest.permission.CAMERA,
            title = "Camera Required",
            rationale = "Vauchi needs the camera to scan your contact's QR code during the exchange.",
        )
    val cameraPermissionGranted = cameraPermState.isGranted
    var permissionsRequested by remember { mutableStateOf(cameraPermissionGranted) }

    LaunchedEffect(cameraPermissionGranted) { if (!cameraPermissionGranted) cameraPermState.request() }
    LaunchedEffect(cameraPermissionGranted) { if (cameraPermissionGranted) permissionsRequested = true }
    PermissionRationaleDialog(cameraPermState)

    // Start multi-stage session on enter
    LaunchedEffect(Unit) {
        viewModel.startMultiStageExchange()
    }

    // Cancel session on exit
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelMultiStageExchange()
        }
    }

    // QR display cycling loop — gets next QR from core on a timer.
    // Core manages the grace period after Complete (cycling VRFY+CONF so slower
    // peers can catch up). We keep calling getDisplayQr() until core returns null.
    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        while (true) {
            val payload = viewModel.getMultiStageDisplayQr()
            if (payload != null) {
                qrBitmap =
                    generateQrBitmapForMultiStage(
                        payload.data,
                        payload.errorCorrection,
                    )
                // Floor at 100ms to prevent tight CPU spin
                delay(maxOf(payload.displayDurationMs.toLong(), 100L))
            } else {
                // Core returned null — grace period expired or failed
                val state = viewModel.getMultiStageState()
                if (state is MobileProtocolState.Finalized) {
                    // Finalize: save the received contact to storage
                    val result = viewModel.finalizeMultiStageExchange()
                    if (result != null) {
                        finalizationResult = result.contactName
                        Log.i("Exchange", "Contact saved: ${result.contactId}")
                    } else {
                        finalizationError = "Failed to save contact"
                        Log.e("Exchange", "Finalization returned null")
                    }
                    graceCompleted = true
                }
                break
            }
            // Refresh protocol state from core
            val state = viewModel.getMultiStageState()
            if (state is MobileProtocolState.Failed) break
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ExchangeBackground)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp).testTag("exchange.back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
                Text(
                    localizationManager.t("exchange.title"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(
                    onClick = { useFrontCamera = !useFrontCamera },
                    modifier = Modifier.size(36.dp).testTag("exchange.camera_switch"),
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = if (useFrontCamera) "Switch to rear camera" else "Switch to front camera",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    ) { padding ->
        when (multiStageState) {
            is MobileProtocolState.Complete, is MobileProtocolState.Finalized -> {
                if (!graceCompleted) {
                    // Keep showing QR while confirming mutual readiness
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .background(ExchangeBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = StatusTextColor, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Completing exchange...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StatusTextColor,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            qrBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Exchange QR code",
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                        }
                    }
                } else {
                    // Grace period over — show success or finalization error
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (finalizationError != null) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Error",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    "Exchange completed but save failed",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    finalizationError!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Contact exchanged!",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Text(
                                    if (finalizationResult != null) {
                                        "$finalizationResult has been added."
                                    } else {
                                        "The new contact has been added."
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
                                        .testTag("exchange.done"),
                            ) {
                                Text(localizationManager.t("action.done"))
                            }
                        }
                    }
                }
            }

            is MobileProtocolState.Failed -> {
                // Exchange failed
                val failedState = multiStageState as MobileProtocolState.Failed
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Failed",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Exchange failed",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            failedState.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Restart the session on retry
                                viewModel.startMultiStageExchange()
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .testTag("exchange.retry"),
                        ) {
                            Text(localizationManager.t("action.retry"))
                        }
                    }
                }
            }

            else -> {
                // Active exchange states: Idle, Advertising, Discovered, Transferring, Verifying, Confirming

                // Tick to refresh scan quality indicator color
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(300L)
                        scanTick++
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(ExchangeBackground),
                ) {
                    if (!cameraPermissionGranted && permissionsRequested) {
                        // Camera permission denied
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier =
                                Modifier
                                    .padding(32.dp)
                                    .align(Alignment.Center),
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Camera access required",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Camera is needed to scan QR codes for contact exchange.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { cameraPermState.request() }, modifier = Modifier.testTag("exchange.grant_permission")) {
                                Text("Grant Permission")
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // === TOP: QR code with generous margins ===
                            Spacer(modifier = Modifier.height(8.dp))

                            qrBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Exchange QR code",
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(0.98f)
                                            .aspectRatio(1f)
                                            .background(
                                                Color(0xFFE0E0E0),
                                                shape = RoundedCornerShape(12.dp),
                                            ).clip(RoundedCornerShape(12.dp)),
                                )
                            } ?: run {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(0.98f)
                                            .aspectRatio(1f)
                                            .background(
                                                Color(0xFFE0E0E0),
                                                shape = RoundedCornerShape(12.dp),
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // === MIDDLE: Instruction text ===
                            Text(
                                text = "Point camera at other phone's QR",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF666666),
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // === BOTTOM: Camera preview + status indicators ===
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                // Small camera preview
                                if (cameraPermissionGranted) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(100.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(
                                                    2.dp,
                                                    Color(0xFF999999),
                                                    RoundedCornerShape(12.dp),
                                                ),
                                    ) {
                                        FaceToFaceCameraPreview(
                                            useFrontCamera = useFrontCamera,
                                            showPreview = true,
                                            onQrCodeDetected = { code ->
                                                if (!scannerGuard.getAndSet(true)) {
                                                    lastScanTimestamp = System.currentTimeMillis()
                                                    coroutineScope.launch {
                                                        viewModel.processMultiStageQr(code)
                                                        delay(50L)
                                                        scannerGuard.set(false)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Status indicators
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Bottom,
                                ) {
                                    MultiStageStatusIndicator(multiStageState)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // === STATUS BAR: scan quality indicator ===
                            ExchangeStatusBar(scanQuality, multiStageState)

                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Text color for status indicators on beige background. */
private val StatusTextColor = Color(0xFF444444)

/**
 * Shows the current multi-stage protocol status with appropriate indicators.
 */
@Composable
private fun MultiStageStatusIndicator(state: MobileProtocolState) {
    when (state) {
        is MobileProtocolState.Idle,
        is MobileProtocolState.Advertising,
        -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Waiting for peer...", style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
            }
        }

        is MobileProtocolState.Discovered -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Peer found! Exchanging...", style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
            }
        }

        is MobileProtocolState.Transferring -> {
            val transferState = state as MobileProtocolState.Transferring
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Transferring...", style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
                }
                Spacer(modifier = Modifier.height(4.dp))
                val receiveProgress =
                    if (transferState.peerChunksTotal > 0u) {
                        transferState.chunksReceived.toFloat() / transferState.peerChunksTotal.toFloat()
                    } else {
                        0f
                    }
                LinearProgressIndicator(
                    progress = { receiveProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Rx ${transferState.chunksReceived}/${transferState.peerChunksTotal} " +
                        "Tx ${transferState.chunksSent}/${transferState.chunksTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusTextColor.copy(alpha = 0.7f),
                )
            }
        }

        is MobileProtocolState.Verifying,
        is MobileProtocolState.Confirming,
        -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Verifying exchange...", style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
            }
        }

        // Complete and Failed are handled in the parent composable
        else -> {}
    }
}

/**
 * Bottom status bar with scan quality color indicator.
 * Green = QR being actively scanned, Orange = recent scan, Red = no scan.
 */
@Composable
private fun ExchangeStatusBar(
    scanQuality: ScanQuality,
    state: MobileProtocolState,
) {
    val indicatorColor =
        when (scanQuality) {
            ScanQuality.GOOD -> Color(0xFF4CAF50)

            // Green
            ScanQuality.FAIR -> Color(0xFFFF9800)

            // Orange
            ScanQuality.NONE -> Color(0xFFF44336) // Red
        }
    val label =
        when (scanQuality) {
            ScanQuality.GOOD -> "Scanning"
            ScanQuality.FAIR -> "Weak signal"
            ScanQuality.NONE -> "No QR detected"
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFEDE8E3)) // Slightly darker beige
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Scan indicator dot
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(indicatorColor, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = StatusTextColor,
        )
    }
}

/**
 * Generate a QR bitmap with the specified error correction level.
 * Used by the multi-stage protocol where the core dictates error correction.
 */
private fun generateQrBitmapForMultiStage(
    data: String,
    errorCorrection: String,
): Bitmap {
    val ecLevel =
        when (errorCorrection.uppercase()) {
            "H" -> com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H
            "Q" -> com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q
            "M" -> com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            else -> com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L
        }
    val writer = QRCodeWriter()
    val hints =
        mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to ecLevel,
            com.google.zxing.EncodeHintType.MARGIN to 3,
        )
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 800, 800, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    // Gray QR: reduces screen glare at close face-to-face distance
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) QR_FOREGROUND else QR_BACKGROUND)
        }
    }
    return bitmap
}

/**
 * Camera preview that supports switching between front and rear cameras.
 * Scans for QR codes and reports them via [onQrCodeDetected].
 *
 * @param showPreview When true, renders a visible camera preview (for the small
 *   preview square). When false, uses a 0dp invisible anchor.
 */
@Composable
fun FaceToFaceCameraPreview(
    useFrontCamera: Boolean,
    showPreview: Boolean = false,
    onQrCodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView =
                if (showPreview) {
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                } else {
                    null
                }

            val rootView = previewView ?: android.view.View(ctx)

            rootView.post {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    // 480p for front camera — best decode rate at close distance
                    val resolutionSelector =
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            ).build()

                    val imageAnalyzer =
                        ImageAnalysis
                            .Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(
                                    cameraExecutor,
                                    QrCodeAnalyzer(
                                        onQrCodeDetected = { code -> onQrCodeDetected(code) },
                                        isFrontCamera = useFrontCamera,
                                    ),
                                )
                            }

                    val cameraSelector =
                        if (useFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                    try {
                        cameraProvider.unbindAll()

                        val useCases =
                            if (showPreview && previewView != null) {
                                val preview =
                                    Preview
                                        .Builder()
                                        .setResolutionSelector(resolutionSelector)
                                        .build()
                                        .also { it.surfaceProvider = previewView.surfaceProvider }
                                arrayOf(preview, imageAnalyzer)
                            } else {
                                arrayOf(imageAnalyzer)
                            }

                        val camera =
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                *useCases,
                            )
                        // Trigger center auto-focus
                        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val action =
                            FocusMeteringAction
                                .Builder(factory.createPoint(0.5f, 0.5f))
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        camera.cameraControl.startFocusAndMetering(action)
                    } catch (e: Exception) {
                        Log.e("FaceToFace", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }

            rootView
        },
        modifier =
            if (showPreview) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(0.dp)
            },
    )
}
