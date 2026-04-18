// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
// ML Kit replaced by rxing via UniFFI — no Google Play Services dependency
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var scannedCode by remember { mutableStateOf<String?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasCameraPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (hasCameraPermission) {
                if (scannedCode == null) {
                    CameraPreview(
                        onQrCodeDetected = { code ->
                            if (code.startsWith("wb://") && scannedCode == null) {
                                scannedCode = code
                            }
                        },
                    )

                    // Overlay with scanning frame
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Scanning frame indicator
                            Box(
                                modifier =
                                    Modifier
                                        .size(250.dp)
                                        .background(Color.Transparent),
                            ) {
                                // Corner indicators
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                ) {
                                    // Visual frame would go here
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Point camera at a Vauchi QR code",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                } else {
                    // Show scanned result
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "QR Code Detected!",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Adding contact...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        CircularProgressIndicator()

                        // Auto-complete exchange
                        LaunchedEffect(scannedCode) {
                            scannedCode?.let { code ->
                                onQrScanned(code)
                            }
                        }
                    }
                }
            } else {
                // No camera permission
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please grant camera permission to scan QR codes",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(onQrCodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview =
                    Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                // 240p is optimal for rxing: 9ms decode, 100% on V4-V10 QR
                val resolutionSelector =
                    ResolutionSelector
                        .Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(320, 240),
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
                                QrCodeAnalyzer(onQrCodeDetected = { code ->
                                    onQrCodeDetected(code)
                                }),
                            )
                        }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer,
                    )
                } catch (e: Exception) {
                    // Handle camera binding errors
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * QR code analyzer using rxing via UniFFI (Rust) instead of ML Kit.
 *
 * Extracts Y-plane directly from camera frame and calls rxing tryHarder
 * for QR decoding. No Google Play Services dependency.
 *
 * At 240p: ~9ms decode, 100% decode rate on V4-V10 QR codes.
 */
class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
    @Suppress("unused") private val saveDir: java.io.File? = null,
    @Suppress("unused") private val maxSaveFrames: Int = 10,
    @Suppress("unused") private val isFrontCamera: Boolean = false,
) : ImageAnalysis.Analyzer {
    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            // Extract Y-plane (luma) directly — no RGB conversion
            val yPlane = mediaImage.planes[0]
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = yPlane.rowStride
            val bytes =
                if (rowStride == width) {
                    val buf = yPlane.buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } else {
                    val buf = yPlane.buffer
                    val data = ByteArray(width * height)
                    for (row in 0 until height) {
                        buf.position(row * rowStride)
                        buf.get(data, row * width, width)
                    }
                    data
                }

            // rxing tryHarder via UniFFI (RqrrPreprocessed = rxing multi-decoder)
            val result =
                uniffi.vauchi_platform.diagnosticScanQr(
                    backend = uniffi.vauchi_platform.MobileScannerBackend.RQRR_PREPROCESSED,
                    lumaData = bytes,
                    width = width.toUInt(),
                    height = height.toUInt(),
                )

            result.decoded?.let { value ->
                android.util.Log.d("QrAnalyzer", "rxing decoded: ${value.take(30)}...")
                onQrCodeDetected(value)
            }
        } catch (e: Exception) {
            android.util.Log.e("QrAnalyzer", "scan error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }
}
