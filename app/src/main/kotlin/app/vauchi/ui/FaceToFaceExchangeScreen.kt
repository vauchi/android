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

/** QR placeholder and background tint: light gray. */
private val QrPlaceholderBackground = Color(0xFFE0E0E0)

/** Instruction text color: medium gray on beige. */
private val InstructionTextColor = Color(0xFF666666)

/** Camera preview border: medium gray. */
private val CameraBorderColor = Color(0xFF999999)

/** Status bar background: slightly darker beige. */
private val StatusBarBackground = Color(0xFFEDE8E3)

/** Scan quality indicator colors. */
private val ScanGoodColor = Color(0xFF4CAF50)
private val ScanFairColor = Color(0xFFFF9800)
private val ScanNoneColor = Color(0xFFF44336)

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

    // Keep screen on during exchange — prevent dimming/lock mid-scan.
    // Also set moderate brightness (65%): max brightness causes camera
    // overexposure on the scanning device, washing out QR module contrast.
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
            title = localizationManager.t("exchange.camera_permission_title"),
            rationale = localizationManager.t("exchange.camera_needed_description"),
        )
    val cameraPermissionGranted = cameraPermState.isGranted
    var permissionsRequested by remember { mutableStateOf(cameraPermissionGranted) }

    LaunchedEffect(cameraPermissionGranted) { if (!cameraPermissionGranted) cameraPermState.request() }
    LaunchedEffect(cameraPermissionGranted) { if (cameraPermissionGranted) permissionsRequested = true }
    PermissionRationaleDialog(cameraPermState)

    // Start exchange on screen entry, restart on re-entry (tab switch).
    // Uses lifecycle ON_RESUME/ON_PAUSE so it works even when the
    // composable is cached in the navigation back stack.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        viewModel.startMultiStageExchange()
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        viewModel.cancelMultiStageExchange()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancelMultiStageExchange()
        }
    }

    // QR display cycling loop — gets next QR from core on a timer.
    // Core manages the grace period after Complete (cycling RDYY so slower
    // peers can catch up). We keep calling getDisplayQr() until core returns null.
    LaunchedEffect(cameraPermissionGranted) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        var cycleCount = 0
        val startMs = System.currentTimeMillis()
        while (true) {
            val payload = viewModel.getMultiStageDisplayQr()
            cycleCount++
            if (payload != null) {
                val elapsed = System.currentTimeMillis() - startMs
                if (cycleCount % 10 == 0) {
                    Log.d(
                        "Vauchi",
                        "Exchange: cycle=$cycleCount t=${elapsed}ms dur=${payload.displayDurationMs}ms",
                    )
                }
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
                val elapsed = System.currentTimeMillis() - startMs
                Log.w("Vauchi", "Exchange: null QR at cycle=$cycleCount t=${elapsed}ms")
                if (state is MobileProtocolState.Finalized && !graceCompleted) {
                    // Finalize: save the received contact to storage
                    val result = viewModel.finalizeMultiStageExchange()
                    if (result != null) {
                        finalizationResult = result.contactName
                        Log.i("Vauchi", "Exchange: contact saved")
                    } else {
                        finalizationError = localizationManager.t("exchange.save_failed")
                        Log.e("Vauchi", "Exchange: finalization returned null")
                    }
                    graceCompleted = true
                }
                break
            }
            // Refresh protocol state from core
            val state = viewModel.getMultiStageState()
            // Save contact immediately on Finalized — don't wait for grace period.
            // The QR loop continues in the background so the peer can still scan.
            if (state is MobileProtocolState.Finalized && !graceCompleted) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.i("Vauchi", "Exchange: finalized at cycle=$cycleCount t=${elapsed}ms")
                val result = viewModel.finalizeMultiStageExchange()
                if (result != null) {
                    finalizationResult = result.contactName
                    Log.i("Vauchi", "Exchange: contact saved")
                } else {
                    finalizationError = localizationManager.t("exchange.save_failed")
                    Log.e("Vauchi", "Exchange: finalization returned null")
                }
                graceCompleted = true
                // Don't break — keep showing RDYY QR so peer can finalize too
            }
            if (state is MobileProtocolState.Failed) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.w("Vauchi", "Exchange: FAILED at cycle=$cycleCount t=${elapsed}ms")
                break
            }
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
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = localizationManager.t("action.back"),
                        modifier = Modifier.size(20.dp),
                    )
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
                        contentDescription =
                            if (useFrontCamera) {
                                localizationManager.t(
                                    "exchange.switch_rear_camera",
                                )
                            } else {
                                localizationManager.t("exchange.switch_front_camera")
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    ) { padding ->
        // Camera and QR live OUTSIDE any when() block so they persist across
        // all state transitions. CameraX never rebinds mid-exchange.
        val isExchangeActive =
            !graceCompleted && multiStageState !is MobileProtocolState.Failed

        if (isExchangeActive) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(300L)
                    scanTick++
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(ExchangeBackground),
        ) {
            // === PERSISTENT LAYER: Camera + QR (always composed while active) ===
            if (isExchangeActive) {
                ExchangeActiveContent(
                    qrBitmap = qrBitmap,
                    cameraPermissionGranted = cameraPermissionGranted,
                    permissionsRequested = permissionsRequested,
                    useFrontCamera = useFrontCamera,
                    multiStageState = multiStageState,
                    scanQuality = scanQuality,
                    localizationManager = localizationManager,
                    onQrScanned = { code ->
                        if (!scannerGuard.getAndSet(true)) {
                            lastScanTimestamp = System.currentTimeMillis()
                            coroutineScope.launch {
                                viewModel.processMultiStageQr(code)
                                delay(50L)
                                scannerGuard.set(false)
                            }
                        }
                    },
                    onRequestPermission = { cameraPermState.request() },
                )
            }

            // === TERMINAL OVERLAY: Success (after grace period) ===
            if (graceCompleted) {
                ExchangeSuccessOverlay(
                    finalizationError = finalizationError,
                    finalizationResult = finalizationResult,
                    localizationManager = localizationManager,
                    onDone = onDone,
                )
            }
            // === TERMINAL OVERLAY: Failed ===
            if (multiStageState is MobileProtocolState.Failed) {
                val failedState = multiStageState as MobileProtocolState.Failed
                ExchangeFailedOverlay(
                    reason = failedState.reason,
                    localizationManager = localizationManager,
                    onRetry = { viewModel.startMultiStageExchange() },
                )
            }
        }
    }
}

/**
 * Active exchange content: QR display, camera preview, status indicators.
 * Shown while the exchange is in progress (not completed or failed).
 */
@Composable
private fun ExchangeActiveContent(
    qrBitmap: Bitmap?,
    cameraPermissionGranted: Boolean,
    permissionsRequested: Boolean,
    useFrontCamera: Boolean,
    multiStageState: MobileProtocolState,
    scanQuality: ScanQuality,
    localizationManager: LocalizationManager,
    onQrScanned: (String) -> Unit,
    onRequestPermission: () -> Unit,
) {
    if (!cameraPermissionGranted && permissionsRequested) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Camera permission required",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    localizationManager.t("exchange.camera_access_required"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    localizationManager.t("exchange.camera_needed_description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRequestPermission, modifier = Modifier.testTag("exchange.grant_permission")) {
                    Text(localizationManager.t("exchange.grant_permission"))
                }
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = localizationManager.t("exchange.qr_code"),
                    modifier =
                        Modifier
                            .fillMaxWidth(0.98f)
                            .aspectRatio(1f)
                            .background(
                                QrPlaceholderBackground,
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
                                QrPlaceholderBackground,
                                shape = RoundedCornerShape(12.dp),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = localizationManager.t("exchange.point_camera"),
                style = MaterialTheme.typography.bodyMedium,
                color = InstructionTextColor,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (cameraPermissionGranted) {
                    Box(
                        modifier =
                            Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    2.dp,
                                    CameraBorderColor,
                                    RoundedCornerShape(12.dp),
                                ),
                    ) {
                        key(useFrontCamera) {
                            FaceToFaceCameraPreview(
                                useFrontCamera = useFrontCamera,
                                showPreview = true,
                                onQrCodeDetected = onQrScanned,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    MultiStageStatusIndicator(multiStageState, localizationManager)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            ExchangeStatusBar(scanQuality, multiStageState, localizationManager)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Success overlay shown after the grace period completes.
 * Displays either a success message with the contact name, or a finalization error.
 */
@Composable
private fun ExchangeSuccessOverlay(
    finalizationError: String?,
    finalizationResult: String?,
    localizationManager: LocalizationManager,
    onDone: () -> Unit,
) {
    // Grace period over — show success or finalization error
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    if (finalizationResult != null) {
                        localizationManager.t("exchange.contact_added", mapOf("name" to finalizationResult))
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
                        .testTag("exchange.done"),
            ) {
                Text(localizationManager.t("action.done"))
            }
        }
    }
}

/**
 * Failed overlay shown when the exchange protocol fails.
 * Displays the failure reason and a retry button.
 */
@Composable
private fun ExchangeFailedOverlay(
    reason: String,
    localizationManager: LocalizationManager,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(ExchangeBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                reason,
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
                        .testTag("exchange.retry"),
            ) {
                Text(localizationManager.t("action.retry"))
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
private fun MultiStageStatusIndicator(
    state: MobileProtocolState,
    localizationManager: LocalizationManager,
) {
    when (state) {
        is MobileProtocolState.Idle,
        is MobileProtocolState.Advertising,
        -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(localizationManager.t("exchange.waiting_peer"), style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
            }
        }

        is MobileProtocolState.Discovered -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(localizationManager.t("exchange.peer_found"), style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
            }
        }

        is MobileProtocolState.Transferring -> {
            val transferState = state as MobileProtocolState.Transferring
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = StatusTextColor, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        localizationManager.t("exchange.transferring"),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusTextColor,
                    )
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
                Text(localizationManager.t("exchange.verifying"), style = MaterialTheme.typography.bodySmall, color = StatusTextColor)
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
    localizationManager: LocalizationManager,
) {
    val indicatorColor =
        when (scanQuality) {
            ScanQuality.GOOD -> ScanGoodColor

            // Green
            ScanQuality.FAIR -> ScanFairColor

            // Orange
            ScanQuality.NONE -> ScanNoneColor
        }
    val label =
        when (scanQuality) {
            ScanQuality.GOOD -> localizationManager.t("exchange.scanning")
            ScanQuality.FAIR -> localizationManager.t("exchange.weak_signal")
            ScanQuality.NONE -> localizationManager.t("exchange.no_qr_detected")
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(StatusBarBackground)
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

                    // 480p — balance between fast ML Kit processing and enough
                    // resolution for QR decoding. 320x240 was too low (Samsung S7
                    // couldn't decode). 640x480 is the sweet spot.
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
                        Log.e("Vauchi", "Exchange: camera binding failed", e)
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
