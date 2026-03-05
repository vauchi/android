// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import com.vauchi.data.ExchangeData
import com.vauchi.data.VauchiRepository
import com.vauchi.util.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Face-to-face exchange screen: shows QR code in the top half and front camera
 * scanner in the bottom half simultaneously. Both users hold phones facing each
 * other and exchange contacts in a single gesture.
 *
 * Ultrasonic proximity verification runs in the background:
 * - While QR is displayed, the device emits its audio challenge
 * - When the front camera scans a QR, it listens briefly and compares
 * - Deterministic emit/listen slot assignment by public ID to avoid collision
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceToFaceExchangeScreen(
    onBack: () -> Unit,
    onGenerateQr: suspend () -> ExchangeData?,
    onQrScanned: (String) -> Unit,
    proximitySupported: Boolean = false,
    onEmitChallenge: (ByteArray) -> Boolean = { false },
    onListenForResponse: (ULong) -> ByteArray? = { null },
    onStopVerification: () -> Unit = {},
    exchangeState: ExchangeFlowState = ExchangeFlowState.Idle,
    onExchangeDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var exchangeData by remember { mutableStateOf<ExchangeData?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var useFrontCamera by remember { mutableStateOf(true) }
    var proximityConfirmed by remember { mutableStateOf(false) }
    var scannerActive by remember { mutableStateOf(true) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionsRequested by remember { mutableStateOf(false) }
    val allPermissionsGranted = cameraPermissionGranted && micPermissionGranted

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            cameraPermissionGranted = results[Manifest.permission.CAMERA] == true
            micPermissionGranted = results[Manifest.permission.RECORD_AUDIO] == true
            permissionsRequested = true
        }

    // Request permissions on first compose
    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        } else {
            permissionsRequested = true
        }
    }

    // Generate QR only after permissions are granted
    LaunchedEffect(retryTrigger, allPermissionsGranted) {
        if (!allPermissionsGranted) return@LaunchedEffect
        isLoading = true
        exchangeData = onGenerateQr()
        exchangeData?.let { data ->
            qrBitmap = generateQrBitmapForFace(data.qrData)
        }
        isLoading = false
    }

    // Emit ultrasonic challenge while QR is displayed
    LaunchedEffect(exchangeData, exchangeState) {
        val challenge = exchangeData?.audioChallenge ?: return@LaunchedEffect
        if (exchangeState !is ExchangeFlowState.Idle) return@LaunchedEffect
        if (!proximitySupported) return@LaunchedEffect

        while (isActive) {
            onEmitChallenge(challenge)
            delay(1500)
        }
    }

    // Stop ultrasonic on dispose
    DisposableEffect(Unit) {
        onDispose {
            onStopVerification()
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
                    // Camera switch button
                    IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = if (useFrontCamera) "Switch to rear camera" else "Switch to front camera",
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (exchangeState) {
            is ExchangeFlowState.Idle -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!allPermissionsGranted && permissionsRequested) {
                        // Permissions denied — show prompt to grant
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
                                "Camera and microphone access required",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Camera scans QR codes. Microphone verifies proximity via ultrasonic.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = {
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                                )
                            }) {
                                Text("Grant Permissions")
                            }
                        }
                    } else if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Spacer(modifier = Modifier.weight(1f))

                            // QR code with camera overlay in center
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .fillMaxWidth(),
                            ) {
                                qrBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Your contact exchange QR code",
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .background(Color.White)
                                                .padding(2.dp),
                                    )
                                }

                                // Hidden camera scanner (no preview, just analysis)
                                if (allPermissionsGranted && scannerActive) {
                                    Box(modifier = Modifier.size(1.dp)) {
                                        FaceToFaceCameraPreview(
                                            useFrontCamera = useFrontCamera,
                                            onQrCodeDetected = { code ->
                                                if (code.startsWith("wb://")) {
                                                    scannerActive = false

                                                    // Ultrasonic listen + verify (non-blocking)
                                                    val scannedChallenge = VauchiRepository.extractAudioChallenge(code)
                                                    if (proximitySupported && scannedChallenge != null) {
                                                        val response = onListenForResponse(3000u)
                                                        if (response != null && response.contentEquals(scannedChallenge)) {
                                                            proximityConfirmed = true
                                                            Log.d("FaceToFace", "Ultrasonic proximity confirmed")
                                                        }
                                                    }

                                                    onQrScanned(code)
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Timer + refresh
                            exchangeData?.let { data ->
                                var remainingSeconds by remember(data.expiresAt) {
                                    mutableLongStateOf(
                                        (data.expiresAt.toLong() - System.currentTimeMillis() / 1000).coerceAtLeast(0),
                                    )
                                }

                                LaunchedEffect(data.expiresAt) {
                                    while (remainingSeconds > 0) {
                                        delay(1000)
                                        remainingSeconds =
                                            (data.expiresAt.toLong() - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                                    }
                                    retryTrigger++
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val minutes = remainingSeconds / 60
                                    val seconds = remainingSeconds % 60
                                    Text(
                                        text =
                                            localizationManager.t(
                                                "exchange.expires_in",
                                                mapOf("time" to String.format("%d:%02d", minutes, seconds)),
                                            ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                            if (remainingSeconds <= 30) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )

                                    TextButton(
                                        onClick = { retryTrigger++ },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Refresh",
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Refresh", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            // Proximity indicator
                            if (proximitySupported) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painter =
                                            androidx.compose.ui.res.painterResource(
                                                android.R.drawable.ic_lock_silent_mode_off,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint =
                                            if (proximityConfirmed) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Text(
                                        text = if (proximityConfirmed) "Proximity verified" else "Ultrasonic active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

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

            is ExchangeFlowState.Completing -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Completing exchange...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (proximityConfirmed) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Proximity verified",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            is ExchangeFlowState.Success -> {
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
                        if (proximityConfirmed) {
                            Text(
                                "Proximity verified via ultrasonic",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "The new contact has been added.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scannerActive = true
                                proximityConfirmed = false
                                onExchangeDone()
                            },
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

            is ExchangeFlowState.Failed -> {
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
                            exchangeState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scannerActive = true
                                proximityConfirmed = false
                                onExchangeDone()
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

            is ExchangeFlowState.PendingProximity -> {
                // Fallback: shouldn't reach here in face-to-face mode
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
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

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx)
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview =
                    Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val resolutionSelector =
                    ResolutionSelector
                        .Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1920, 1080),
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
                                QrCodeAnalyzer(onQrCodeDetected = { code -> onQrCodeDetected(code) }),
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
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer,
                    )
                } catch (e: Exception) {
                    Log.e("FaceToFace", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun generateQrBitmapForFace(data: String): Bitmap {
    val writer = QRCodeWriter()
    val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
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
