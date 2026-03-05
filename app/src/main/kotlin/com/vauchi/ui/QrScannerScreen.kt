// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

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
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
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

class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
    private val saveDir: java.io.File? = null,
    private val maxSaveFrames: Int = 10,
    private val isFrontCamera: Boolean = false,
) : ImageAnalysis.Analyzer {
    private val hints =
        mapOf(
            com.google.zxing.DecodeHintType.TRY_HARDER to true,
            com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
        )
    private val multiReader =
        com.google.zxing
            .MultiFormatReader()
            .apply { setHints(hints) }
    private var frameCount = 0
    private var savedFrameCount = 0

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val rotation = imageProxy.imageInfo.rotationDegrees

            frameCount++
            if (frameCount % 60 == 1) {
                android.util.Log.d(
                    "QrAnalyzer",
                    "Frame #$frameCount: ${imageProxy.width}x${imageProxy.height} stride=$rowStride rot=$rotation",
                )
            }

            // Rotate Y-plane data to upright orientation if needed
            val sensorW = imageProxy.width
            val sensorH = imageProxy.height
            val (rotatedBytes, rotatedW, rotatedH) =
                when (rotation) {
                    90 -> {
                        val out = ByteArray(sensorW * sensorH)
                        for (y in 0 until sensorH) {
                            for (x in 0 until sensorW) {
                                out[x * sensorH + (sensorH - 1 - y)] = bytes[y * rowStride + x]
                            }
                        }
                        Triple(out, sensorH, sensorW)
                    }

                    270 -> {
                        val out = ByteArray(sensorW * sensorH)
                        for (y in 0 until sensorH) {
                            for (x in 0 until sensorW) {
                                out[(sensorW - 1 - x) * sensorH + y] = bytes[y * rowStride + x]
                            }
                        }
                        Triple(out, sensorH, sensorW)
                    }

                    180 -> {
                        val out = ByteArray(sensorW * sensorH)
                        for (y in 0 until sensorH) {
                            for (x in 0 until sensorW) {
                                out[(sensorH - 1 - y) * sensorW + (sensorW - 1 - x)] = bytes[y * rowStride + x]
                            }
                        }
                        Triple(out, sensorW, sensorH)
                    }

                    else -> {
                        // 0° — copy without stride padding
                        if (rowStride == sensorW) {
                            Triple(bytes, sensorW, sensorH)
                        } else {
                            val out = ByteArray(sensorW * sensorH)
                            for (y in 0 until sensorH) {
                                System.arraycopy(bytes, y * rowStride, out, y * sensorW, sensorW)
                            }
                            Triple(out, sensorW, sensorH)
                        }
                    }
                }

            // Save rotated camera frames for diagnosis
            if (saveDir != null && savedFrameCount < maxSaveFrames && frameCount % 30 == 1) {
                try {
                    saveDir.mkdirs()
                    val bmp = android.graphics.Bitmap.createBitmap(rotatedW, rotatedH, android.graphics.Bitmap.Config.ARGB_8888)
                    for (y in 0 until rotatedH) {
                        for (x in 0 until rotatedW) {
                            val lum = rotatedBytes[y * rotatedW + x].toInt() and 0xFF
                            bmp.setPixel(x, y, (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum)
                        }
                    }
                    val outFile = java.io.File(saveDir, "frame_$savedFrameCount.png")
                    java.io.FileOutputStream(outFile).use { fos ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    bmp.recycle()
                    savedFrameCount++
                    android.util.Log.d("QrAnalyzer", "Saved frame $savedFrameCount to ${outFile.absolutePath}")
                } catch (e: Exception) {
                    android.util.Log.w("QrAnalyzer", "Failed to save frame: ${e.message}")
                }
            }

            // Front camera produces mirrored frames — try mirrored first for speed.
            val primaryMirrored = isFrontCamera

            // Try full frame first, then center crop (2x zoom) for small/distant QR codes
            val detected =
                tryDecodeQr(rotatedBytes, rotatedW, rotatedH, primaryMirrored)
                    ?: run {
                        // Center crop: middle 50% of frame = effectively 2x digital zoom
                        val cropW = rotatedW / 2
                        val cropH = rotatedH / 2
                        val cropX = (rotatedW - cropW) / 2
                        val cropY = (rotatedH - cropH) / 2
                        tryDecodeQr(rotatedBytes, rotatedW, rotatedH, primaryMirrored, cropX, cropY, cropW, cropH)
                    }

            detected?.let { value ->
                android.util.Log.d("QrAnalyzer", "QR detected: ${value.take(30)}...")
                onQrCodeDetected(value)
            }
        } catch (_: NotFoundException) {
            // No QR code found in this frame — normal during scanning
        } catch (e: Exception) {
            if (frameCount % 60 == 2) {
                android.util.Log.d("QrAnalyzer", "Decode error: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun tryDecodeQr(
        data: ByteArray,
        dataWidth: Int,
        dataHeight: Int,
        mirrored: Boolean,
        cropX: Int = 0,
        cropY: Int = 0,
        cropW: Int = dataWidth,
        cropH: Int = dataHeight,
    ): String? =
        try {
            val source = PlanarYUVLuminanceSource(data, dataWidth, dataHeight, cropX, cropY, cropW, cropH, mirrored)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = multiReader.decodeWithState(bitmap)
            multiReader.reset()
            result.text
        } catch (_: NotFoundException) {
            multiReader.reset()
            // Try opposite mirror
            try {
                val source = PlanarYUVLuminanceSource(data, dataWidth, dataHeight, cropX, cropY, cropW, cropH, !mirrored)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = multiReader.decodeWithState(bitmap)
                multiReader.reset()
                result.text
            } catch (_: NotFoundException) {
                multiReader.reset()
                null
            }
        }
}
