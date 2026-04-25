// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
// ML Kit replaced by rxing via UniFFI — no Google Play Services dependency
import java.util.concurrent.Executors
import uniffi.vauchi_platform.MobileMultipartDecoder

/**
 * Continuous QR scanner that tracks multipart reassembly progress.
 *
 * Scans QR codes via CameraX + rxing, then feeds each detected chunk into
 * core's [MobileMultipartDecoder] for parsing, CRC32 validation, duplicate
 * detection, and final assembly. The frontend never parses the chunk header
 * itself — per ADR-021 (Humble UI), reassembly is core logic.
 *
 * Shows a progress bar driven by the core decoder's `received()` /
 * `expectedTotal()` accessors and calls [onComplete] with the assembled
 * payload bytes when [MobileMultipartDecoder.isComplete] flips true.
 *
 * @param onComplete Callback invoked with the assembled payload bytes once
 *   all chunks have been received and CRC-validated by core.
 * @param onCancel Callback invoked when the user presses the cancel button.
 * @param modifier Modifier for the root layout.
 */
@Composable
fun MultipartQRScanner(
    onComplete: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

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

    // Reassembly state owned by core. Compose only mirrors the counters
    // the decoder exposes for the progress UI; the chunk Map<Int, String>
    // and ad-hoc completeness check that used to live here are gone.
    val decoder = remember { MobileMultipartDecoder() }
    DisposableEffect(decoder) {
        onDispose { decoder.destroy() }
    }
    var receivedCount by remember { mutableIntStateOf(0) }
    var totalChunks by remember { mutableIntStateOf(0) }
    var isComplete by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Multipart QR scanner"
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasCameraPermission && !isComplete) {
            // Camera preview
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                MultipartCameraPreview(
                    onChunkScanned = { rawValue ->
                        try {
                            decoder.addChunk(rawValue)
                            receivedCount = decoder.received().toInt()
                            totalChunks = decoder.expectedTotal()?.toInt() ?: 0
                            if (decoder.isComplete()) {
                                isComplete = true
                                onComplete(decoder.assemble())
                            }
                        } catch (e: Exception) {
                            // Malformed / CRC-mismatched chunk: core rejects it,
                            // we drop it silently and keep scanning.
                            android.util.Log.d("MultipartQR", "decoder rejected chunk: ${e.message}")
                        }
                    },
                )

                // Overlay scanning hint
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(250.dp),
                    )
                }
            }

            // Progress section
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (totalChunks > 0) {
                    Text(
                        text = "Received $receivedCount of $totalChunks parts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    LinearProgressIndicator(
                        progress = { receivedCount.toFloat() / totalChunks },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                } else {
                    Text(
                        text = "Scanning for QR codes...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        } else if (isComplete) {
            // Completion state
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "All parts received!",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Processing...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
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
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Camera preview composable for continuous multipart QR scanning.
 *
 * Uses CameraX with ZXing QR decoding. Shorter debounce than the
 * single-shot scanner in [app.vauchi.ui.QrScannerScreen] since we need
 * to capture many different codes in quick succession.
 *
 * @param onChunkScanned Callback invoked with each detected QR code's raw value.
 */
@Composable
private fun MultipartCameraPreview(onChunkScanned: (String) -> Unit) {
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
                                MultipartQRAnalyzer(onChunkScanned),
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
                } catch (_: Exception) {
                    // Handle camera binding errors
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Multipart QR analyzer using rxing via UniFFI. Extracts the Y-plane from
 * each frame and runs core's `scanQr` against it.
 */
private class MultipartQRAnalyzer(
    private val onChunkScanned: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastScanTimeMs = 0L

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastScanTimeMs < SCAN_DEBOUNCE_MS) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
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

            val result =
                uniffi.vauchi_platform.scanQr(
                    backend = uniffi.vauchi_platform.MobileScannerBackend.RQRR_PREPROCESSED,
                    lumaData = bytes,
                    width = width.toUInt(),
                    height = height.toUInt(),
                )

            result.decoded?.let { value ->
                lastScanTimeMs = System.currentTimeMillis()
                onChunkScanned(value)
            }
        } catch (e: Exception) {
            android.util.Log.e("MultipartQR", "scan error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 50L // faster than ML Kit's 100ms
    }
}
