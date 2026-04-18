// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.Manifest
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.diagnostic.qr.CameraConfigTuner
import app.vauchi.diagnostic.qr.RustScannerBridge
import app.vauchi.diagnostic.qr.ScannerMode
import app.vauchi.util.generateQrBitmap
import uniffi.vauchi_platform.MobileQrEccLevel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "QrDiag"

/**
 * QR Code diagnostic screen — tests QR generation, camera capture, and Rust rqrr
 * detection with configurable parameters. Use this to find the optimal setup
 * for the multi-stage exchange protocol.
 *
 * Features:
 * - Generates QR codes at different complexity levels (matching protocol stages)
 * - Front/rear camera toggle
 * - Live detection stats (frames, detections, resolution)
 * - Uses Rust rqrr scanner via UniFFI (RustScannerBridge)
 * - Shows last detected content and timing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrDiagnosticScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermission = granted
        }
    LaunchedEffect(Unit) {
        if (!cameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // --- Configurable state ---
    var useFrontCamera by remember { mutableStateOf(true) }
    var qrComplexity by remember { mutableStateOf(QrTestLevel.INIT) }
    var ecLevel by remember { mutableStateOf("M") }
    var zoomRatio by remember { mutableFloatStateOf(1.0f) }

    // --- Detection stats (updated from analyzer thread) ---
    var cameraRes by remember { mutableStateOf("1080p") }
    val stats = remember { DiagnosticStats() }
    val frameCount by stats.frameCountState
    val detectionCount by stats.detectionCountState
    val lastDetected by stats.lastDetectedState
    val lastError by stats.lastErrorState
    val resolution by stats.resolutionState
    val hybridHits by stats.hybridHitsState
    val globalHits by stats.globalHitsState
    val cropHits by stats.cropHitsState
    // Generate the test QR bitmap
    val qrContent = remember(qrComplexity) { qrComplexity.sampleContent() }
    val ec =
        remember(ecLevel) {
            when (ecLevel) {
                "L" -> MobileQrEccLevel.LOW
                "Q" -> MobileQrEccLevel.QUARTILE
                "H" -> MobileQrEccLevel.HIGH
                else -> MobileQrEccLevel.MEDIUM
            }
        }
    val qrBitmap =
        remember(qrContent, ecLevel) {
            generateQrBitmap(data = qrContent, size = 512, errorCorrection = ec, margin = 2)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR Diagnostic") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Toggle camera")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            // --- QR Display ---
            Text(
                "Test QR (${qrContent.length} chars, EC=$ecLevel, ${qrComplexity.name})",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            qrBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Test QR",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(horizontal = 0.dp)
                            .background(Color.White),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Controls ---
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QrTestLevel.entries.forEach { level ->
                    FilterChip(
                        selected = qrComplexity == level,
                        onClick = {
                            qrComplexity = level
                            stats.reset()
                        },
                        label = { Text(level.label, fontSize = 11.sp) },
                    )
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("L", "M", "Q", "H").forEach { ec ->
                    FilterChip(
                        selected = ecLevel == ec,
                        onClick = {
                            ecLevel = ec
                            stats.reset()
                        },
                        label = { Text("EC-$ec", fontSize = 11.sp) },
                    )
                }
            }

            // --- Camera resolution picker ---
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("640p", "720p", "1080p").forEach { res ->
                    FilterChip(
                        selected = cameraRes == res,
                        onClick = {
                            cameraRes = res
                            stats.reset()
                        },
                        label = { Text(res, fontSize = 11.sp) },
                    )
                }
            }

            // --- Zoom slider ---
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Zoom: ${"%.1f".format(zoomRatio)}x", fontSize = 12.sp, modifier = Modifier.width(72.dp))
                Slider(
                    value = zoomRatio,
                    onValueChange = { zoomRatio = it },
                    valueRange = 1.0f..4.0f,
                    steps = 5,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Live Stats ---
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Detection Stats", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    StatRow("Camera", if (useFrontCamera) "Front" else "Rear")
                    StatRow("Resolution", resolution)
                    StatRow("Frames", "$frameCount")
                    StatRow("Detections", "$detectionCount")
                    StatRow("Rate", if (frameCount > 0) "${"%.1f".format(detectionCount * 100.0 / frameCount)}%" else "—")
                    if (lastDetected.isNotEmpty()) {
                        StatRow("Last detected", lastDetected.take(60) + if (lastDetected.length > 60) "..." else "")
                    }
                    if (lastError.isNotEmpty()) {
                        StatRow("Last error", lastError, isError = true)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Camera scanner (invisible — analysis only) ---
            if (cameraPermission) {
                DiagnosticCameraScanner(
                    useFrontCamera = useFrontCamera,
                    stats = stats,
                    zoomRatio = zoomRatio,
                    cameraRes = cameraRes,
                )
            } else {
                Text(
                    "Camera permission required",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    isError: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(100.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// --- Test QR complexity levels matching protocol stages ---

enum class QrTestLevel(
    val label: String,
) {
    TINY("Tiny 10ch") {
        override fun sampleContent() = "HELLO12345"
    },
    SHORT("Short 50ch") {
        override fun sampleContent() = "INIT|" + "A".repeat(45)
    },
    INIT("INIT ~190ch") {
        override fun sampleContent(): String {
            // Simulate real INIT payload: 4 + 5 separators + 24 + 48 + 48 + 48 + 11 = ~188 chars
            val sid = "0123456789ABCDEFGHIJKLMN" // 24 chars (base45 of 16 bytes)
            val pk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLM" // 48 chars
            val eph = "NOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" // 48 chars
            val ch = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ012345678901AB" // 48 chars
            return "INIT|$sid|$pk|$eph|$ch|Vauchi User"
        }
    },
    DATA("DATA ~700ch") {
        override fun sampleContent(): String {
            // Simulate DATA payload: header + base45 chunk payload
            val sid = "0123456789ABCDEFGHIJKLMN"
            val payload =
                (0 until 450).joinToString("") {
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[it % 36].toString()
                }
            return "DATA|$sid|0/1|FF|A1B2|$payload"
        }
    }, ;

    abstract fun sampleContent(): String
}

// --- Diagnostic stats (thread-safe) ---

class DiagnosticStats {
    private val _frameCount = AtomicInteger(0)
    private val _detectionCount = AtomicInteger(0)
    private val _hybridHits = AtomicInteger(0)
    private val _globalHits = AtomicInteger(0)
    private val _cropHits = AtomicInteger(0)
    private val _lastDetected = AtomicReference("")
    private val _lastError = AtomicReference("")
    private val _resolution = AtomicReference("—")
    private val _lastUpdateMs = AtomicLong(0)

    // Compose state wrappers (read on main thread)
    val frameCountState = mutableIntStateOf(0)
    val detectionCountState = mutableIntStateOf(0)
    val hybridHitsState = mutableIntStateOf(0)
    val globalHitsState = mutableIntStateOf(0)
    val cropHitsState = mutableIntStateOf(0)
    val lastDetectedState = mutableStateOf("")
    val lastErrorState = mutableStateOf("")
    val resolutionState = mutableStateOf("—")

    fun recordFrame(
        width: Int,
        height: Int,
        rotation: Int,
    ) {
        _frameCount.incrementAndGet()
        _resolution.set("${width}x$height rot=$rotation")
        syncToCompose()
    }

    fun recordDetection(
        content: String,
        method: String,
    ) {
        _detectionCount.incrementAndGet()
        _lastDetected.set(content)
        when {
            "crop" in method -> _cropHits.incrementAndGet()
            "global" in method -> _globalHits.incrementAndGet()
            else -> _hybridHits.incrementAndGet()
        }
        syncToCompose()
    }

    fun recordError(error: String) {
        _lastError.set(error)
        syncToCompose()
    }

    fun reset() {
        _frameCount.set(0)
        _detectionCount.set(0)
        _hybridHits.set(0)
        _globalHits.set(0)
        _cropHits.set(0)
        _lastDetected.set("")
        _lastError.set("")
        syncToCompose()
    }

    private fun syncToCompose() {
        // Throttle UI updates to ~10Hz
        val now = System.currentTimeMillis()
        if (now - _lastUpdateMs.get() < 100) return
        _lastUpdateMs.set(now)
        frameCountState.intValue = _frameCount.get()
        detectionCountState.intValue = _detectionCount.get()
        hybridHitsState.intValue = _hybridHits.get()
        globalHitsState.intValue = _globalHits.get()
        cropHitsState.intValue = _cropHits.get()
        lastDetectedState.value = _lastDetected.get()
        lastErrorState.value = _lastError.get()
        resolutionState.value = _resolution.get()
    }
}

// --- Diagnostic camera scanner ---

@Composable
private fun DiagnosticCameraScanner(
    useFrontCamera: Boolean,
    stats: DiagnosticStats,
    zoomRatio: Float = 1.0f,
    cameraRes: String = "1080p",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraRef by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // Apply zoom when slider changes
    LaunchedEffect(zoomRatio) {
        cameraRef?.cameraControl?.setZoomRatio(zoomRatio)
    }

    val targetSize =
        when (cameraRes) {
            "640p" -> android.util.Size(640, 480)
            "720p" -> android.util.Size(1280, 720)
            else -> android.util.Size(1920, 1080)
        }

    // Rebind camera when front/rear toggle or resolution changes
    key(useFrontCamera, cameraRes) {
        AndroidView(
            factory = { ctx ->
                android.view.View(ctx).apply {
                    post {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val resolutionSelector =
                                ResolutionSelector
                                    .Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            targetSize,
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
                                            RustQrAnalyzer(stats = stats),
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
                                cameraRef = camera
                                camera.cameraControl.setZoomRatio(zoomRatio)
                                // Trigger center auto-focus
                                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                                val centerPoint = factory.createPoint(0.5f, 0.5f)
                                val action =
                                    FocusMeteringAction
                                        .Builder(centerPoint)
                                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                camera.cameraControl.startFocusAndMetering(action)
                                Log.i(TAG, "Camera bound: front=$useFrontCamera res=$cameraRes zoom=$zoomRatio af=center")
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera binding failed", e)
                                stats.recordError("Camera: ${e.message}")
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            },
            modifier = Modifier.size(1.dp),
        )
    }
}

// --- Rust QR analyzer (replaces ML Kit) ---

class RustQrAnalyzer(
    private val stats: DiagnosticStats,
) : ImageAnalysis.Analyzer {
    private var frameIdx = 0

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        frameIdx++
        val rotation = imageProxy.imageInfo.rotationDegrees
        stats.recordFrame(imageProxy.width, imageProxy.height, rotation)

        try {
            val bytes = CameraConfigTuner.extractYPlane(mediaImage)
            val text =
                RustScannerBridge.scan(
                    ScannerMode.RqrrPreprocessed,
                    bytes,
                    mediaImage.width,
                    mediaImage.height,
                )
            if (text != null) {
                Log.i(TAG, "DETECTED [rqrr]: ${text.take(40)}...")
                stats.recordDetection(text, "rqrr")
            }
        } catch (e: Exception) {
            if (frameIdx % 60 == 1) {
                Log.w(TAG, "Rust scanner error: ${e.message}")
                stats.recordError("rqrr: ${e.message}")
            }
        } finally {
            imageProxy.close()
        }
    }
}
