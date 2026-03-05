// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vauchi.util.LocalizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.vauchi_mobile.MobileProtocolState
import uniffi.vauchi_mobile.MobileQrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
        val previousBrightness = activity?.window?.attributes?.screenBrightness
        activity?.window?.attributes =
            activity?.window?.attributes?.apply {
                screenBrightness = 1.0f
            }
        onDispose {
            activity?.window?.attributes =
                activity?.window?.attributes?.apply {
                    screenBrightness = previousBrightness ?: -1.0f
                }
        }
    }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var useFrontCamera by remember { mutableStateOf(true) }
    val scannerGuard = remember { AtomicBoolean(false) }
    val multiStageState by viewModel.multiStageState.collectAsState()

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionsRequested by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionGranted = granted
            permissionsRequested = true
        }

    // Request camera permission on first compose
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            permissionsRequested = true
        }
    }

    // Start multi-stage session on enter
    LaunchedEffect(Unit) {
        // TODO: Replace placeholder with actual serialized local contact card
        // e.g., viewModel.getLocalContactCardBytes()
        val localCard = "Vauchi User".toByteArray()
        viewModel.startMultiStageExchange(localCard)
    }

    // Cancel session on exit
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelMultiStageExchange()
        }
    }

    // QR display cycling loop — gets next QR from core on a timer
    LaunchedEffect(cameraPermissionGranted, multiStageState) {
        if (!cameraPermissionGranted) return@LaunchedEffect
        // Keep cycling while session is active (not Complete or Failed)
        while (multiStageState !is MobileProtocolState.Complete &&
            multiStageState !is MobileProtocolState.Failed
        ) {
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
                // No QR to display yet, poll at default rate
                delay(300L)
            }
            // Refresh protocol state from core
            viewModel.getMultiStageState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("exchange.title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription =
                                if (useFrontCamera) {
                                    "Switch to rear camera"
                                } else {
                                    "Switch to front camera"
                                },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (multiStageState) {
            is MobileProtocolState.Complete -> {
                // Exchange completed successfully
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
                            "The new contact has been added.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onDone,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                        ) {
                            Text(localizationManager.t("action.done"))
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
                                val localCard = "Vauchi User".toByteArray()
                                viewModel.startMultiStageExchange(localCard)
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                        ) {
                            Text(localizationManager.t("action.retry"))
                        }
                    }
                }
            }

            else -> {
                // Active exchange states: Idle, Advertising, Discovered, Transferring, Verifying, Confirming
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!cameraPermissionGranted && permissionsRequested) {
                        // Camera permission denied
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp),
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
                            Button(onClick = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }) {
                                Text("Grant Permission")
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Spacer(modifier = Modifier.weight(1f))

                            // Status text
                            MultiStageStatusIndicator(multiStageState)

                            Spacer(modifier = Modifier.height(8.dp))

                            // QR code display with hidden camera scanner
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                qrBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Exchange QR code",
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .background(Color.White),
                                    )
                                }

                                // Hidden camera scanner — continuous scanning
                                if (cameraPermissionGranted) {
                                    Box(modifier = Modifier.size(1.dp)) {
                                        FaceToFaceCameraPreview(
                                            useFrontCamera = useFrontCamera,
                                            onQrCodeDetected = { code ->
                                                // Allow re-scanning after processing — the core
                                                // needs multiple QR frames for the chunked protocol.
                                                // Use scannerGuard to dedup consecutive identical frames.
                                                if (!scannerGuard.getAndSet(true)) {
                                                    coroutineScope.launch {
                                                        viewModel.processMultiStageQr(code)
                                                        // Reset guard after a short delay to allow
                                                        // the next distinct QR frame to be processed
                                                        delay(50L)
                                                        scannerGuard.set(false)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Point camera at other phone's QR",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

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
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Waiting for peer...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is MobileProtocolState.Discovered -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Peer found! Exchanging data...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is MobileProtocolState.Transferring -> {
            val transferState = state as MobileProtocolState.Transferring
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Transferring data...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val sendProgress =
                    if (transferState.chunksTotal > 0u) {
                        transferState.chunksSent.toFloat() / transferState.chunksTotal.toFloat()
                    } else {
                        0f
                    }
                val receiveProgress =
                    if (transferState.peerChunksTotal > 0u) {
                        transferState.chunksReceived.toFloat() / transferState.peerChunksTotal.toFloat()
                    } else {
                        0f
                    }
                LinearProgressIndicator(
                    progress = { receiveProgress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Sent ${transferState.chunksSent}/${transferState.chunksTotal} " +
                        "Received ${transferState.chunksReceived}/${transferState.peerChunksTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is MobileProtocolState.Verifying,
        is MobileProtocolState.Confirming,
        -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Verifying exchange...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Complete and Failed are handled in the parent composable
        else -> {}
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
            com.google.zxing.EncodeHintType.MARGIN to 1,
        )
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 1024, 1024, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bitmap
}

/**
 * Camera preview that supports switching between front and rear cameras.
 * Scans for QR codes and reports them via [onQrCodeDetected].
 */
@Composable
fun FaceToFaceCameraPreview(
    useFrontCamera: Boolean,
    onQrCodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // Use an invisible AndroidView just to anchor the lifecycle-aware camera.
    // We only bind ImageAnalysis (no Preview use case) so no surface is needed,
    // which avoids buffer dequeue failures on devices like Pixel 3a.
    AndroidView(
        factory = { ctx ->
            android.view.View(ctx).apply {
                // Trigger camera setup once the view is attached
                post {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        // 720p works better than 1080p on front cameras (less noise for ML Kit)
                        val resolutionSelector =
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1280, 720),
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
                            val camera =
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    imageAnalyzer,
                                )
                            // Apply 1.5x zoom on front camera for better QR detection at distance
                            if (useFrontCamera) {
                                camera.cameraControl.setZoomRatio(1.5f)
                            }
                            // Trigger center auto-focus
                            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                            val action =
                                FocusMeteringAction
                                    .Builder(factory.createPoint(0.5f, 0.5f))
                                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                            camera.cameraControl.startFocusAndMetering(action)
                        } catch (e: Exception) {
                            Log.e("FaceToFace", "Camera binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        },
        modifier = Modifier.size(0.dp),
    )
}
