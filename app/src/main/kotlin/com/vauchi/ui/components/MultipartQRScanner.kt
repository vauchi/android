// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.components

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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Continuous QR scanner that tracks multipart reassembly progress.
 *
 * Scans QR codes using CameraX + ML Kit and parses the multipart chunk header
 * format: `{index}/{total}/{crc32_hex}/{base64url_data}`.
 *
 * Shows a progress bar as chunks are received and calls [onComplete] when all
 * chunks have been collected.
 *
 * @param onComplete Callback invoked with all received chunk strings (ordered by index) when reassembly is complete.
 * @param onCancel Callback invoked when the user presses the cancel button.
 * @param modifier Modifier for the root layout.
 */
@Composable
fun MultipartQRScanner(
    onComplete: (List<String>) -> Unit,
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

    // Chunk tracking state
    // Key: chunk index (0-based), Value: raw scanned string
    val receivedChunks = remember { mutableStateMapOf<Int, String>() }
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
                        // TODO: When vauchi-mobile-android publishes MobileMultipartDecoder,
                        // replace this local parsing with the Rust-backed decoder for
                        // proper CRC32 validation and base64url decoding.
                        val parsed = parseChunkHeader(rawValue)
                        if (parsed != null) {
                            val (index, total, _) = parsed
                            if (totalChunks == 0) {
                                totalChunks = total
                            }
                            if (total == totalChunks && index < total) {
                                receivedChunks[index] = rawValue

                                // Check completeness
                                if (receivedChunks.size == totalChunks) {
                                    isComplete = true
                                    val ordered =
                                        (0 until totalChunks).map { i ->
                                            receivedChunks[i] ?: ""
                                        }
                                    onComplete(ordered)
                                }
                            }
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
                        text = "Received ${receivedChunks.size} of $totalChunks parts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    LinearProgressIndicator(
                        progress = { receivedChunks.size.toFloat() / totalChunks },
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
 * single-shot scanner in [com.vauchi.ui.QrScannerScreen] since we need
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
 * Image analyzer for continuous multipart QR code scanning.
 *
 * Uses ML Kit barcode scanner with a short debounce (100ms) to allow rapid
 * scanning of different QR codes as they cycle on the other device's display.
 */
private class MultipartQRAnalyzer(
    private val onChunkScanned: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner =
        BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
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

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner
            .process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { value ->
                        lastScanTimeMs = System.currentTimeMillis()
                        onChunkScanned(value)
                    }
                }
            }.addOnCompleteListener {
                imageProxy.close()
            }
    }

    companion object {
        /** Debounce interval in milliseconds between scans. */
        private const val SCAN_DEBOUNCE_MS = 100L
    }
}

/**
 * Parses a multipart QR chunk header.
 *
 * Expected format: `{index}/{total}/{crc32_hex}/{base64url_data}`
 *
 * TODO: When vauchi-mobile-android publishes MobileMultipartDecoder bindings,
 * replace this with the Rust-backed decoder for proper CRC32 validation.
 *
 * @param raw The raw QR code content string.
 * @return A triple of (index, total, data) or null if parsing fails.
 */
internal fun parseChunkHeader(raw: String): Triple<Int, Int, String>? {
    // Format: {index}/{total}/{crc32_hex}/{base64url_data}
    val parts = raw.split("/", limit = 4)
    if (parts.size != 4) return null

    val index = parts[0].toIntOrNull() ?: return null
    val total = parts[1].toIntOrNull() ?: return null
    val crc32Hex = parts[2]
    val data = parts[3]

    // Basic validation
    if (index < 0 || total <= 0 || index >= total) return null
    if (crc32Hex.isBlank() || data.isBlank()) return null

    return Triple(index, total, data)
}
