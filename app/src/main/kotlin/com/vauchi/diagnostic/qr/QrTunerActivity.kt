// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.vauchi_mobile.MobileCameraConfig
import uniffi.vauchi_mobile.MobileDeviceCapabilityProfile
import uniffi.vauchi_mobile.MobileQrConfig
import uniffi.vauchi_mobile.MobileScoredConfig
import uniffi.vauchi_mobile.MobileSweepMatrix
import uniffi.vauchi_mobile.MobileTuningResult
import uniffi.vauchi_mobile.diagnosticGenerateSweepMatrix
import uniffi.vauchi_mobile.diagnosticRankConfigs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostic activity that sweeps camera configurations to find optimal
 * QR scanning parameters for this device.
 *
 * Launch via:
 *   adb shell am start -n com.vauchi/.diagnostic.qr.QrTunerActivity
 */
@ExperimentalCamera2Interop
class QrTunerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "QRTuner"
    }

    private var cameraPermissionGranted by mutableStateOf(false)
    private var profile by mutableStateOf<MobileDeviceCapabilityProfile?>(null)
    private var sweepMatrix by mutableStateOf<MobileSweepMatrix?>(null)
    private var sweepRunning by mutableStateOf(false)
    private var sweepProgress by mutableStateOf(0f)
    private var currentConfigId by mutableStateOf<UInt?>(null)
    private var thermalTemp by mutableStateOf(0f)
    private val logLines = mutableStateListOf<String>()
    private var rankedResults by mutableStateOf<List<MobileScoredConfig>>(emptyList())
    private var sweepDone by mutableStateOf(false)

    private var camera: Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Deferred for frame decode results from the image analyzer (atomic read-and-clear)
    private val pendingFrame = AtomicReference<CompletableDeferred<FrameResult>?>(null)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionGranted = granted
            if (granted) {
                initProbe()
            } else {
                log("ERROR: Camera permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TunerScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun initProbe() {
        CoroutineScope(Dispatchers.Default).launch {
            val p = DeviceCapabilityProbe.probe(this@QrTunerActivity)
            val matrix = diagnosticGenerateSweepMatrix(p)
            withContext(Dispatchers.Main) {
                profile = p
                sweepMatrix = matrix
                log("Device: ${p.deviceModel}")
                log("HW Level: ${p.hardwareLevel ?: "unknown"}")
                log("ISO: ${p.isoRangeMin ?: "?"}-${p.isoRangeMax ?: "?"}")
                log("Configs: ${matrix.cameraConfigs.size} camera x ${matrix.qrConfigs.size} QR")
            }
        }
    }

    private fun startSweep() {
        val matrix = sweepMatrix ?: return
        if (sweepRunning) return
        sweepRunning = true
        sweepDone = false
        rankedResults = emptyList()

        CoroutineScope(Dispatchers.Default).launch {
            runSweep(matrix)
        }
    }

    private suspend fun runSweep(matrix: MobileSweepMatrix) {
        val thermalMonitor = ThermalMonitor(this)
        val tuner = CameraConfigTuner(thermalMonitor)
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val capture = DiagnosticCapture(this, sessionId)
        val allResults = mutableListOf<MobileTuningResult>()
        val totalConfigs = matrix.cameraConfigs.size
        val qrConfig = matrix.qrConfigs.firstOrNull() ?: return

        for ((index, cameraConfig) in matrix.cameraConfigs.withIndex()) {
            withContext(Dispatchers.Main) {
                currentConfigId = cameraConfig.id
                sweepProgress = index.toFloat() / totalConfigs.toFloat()
                thermalTemp = thermalMonitor.getTemperatureCelsius()
            }
            log("Config ${cameraConfig.id} (${index + 1}/$totalConfigs)")

            // Apply config to camera
            val cam = camera
            if (cam != null) {
                withContext(Dispatchers.Main) {
                    tuner.applyConfig(cam, cameraConfig)
                }
            }

            val runResult =
                tuner.runConfig(cameraConfig.id) {
                    captureAndDecodeFrame()
                }

            val tuningResult =
                tuner.toTuningResult(
                    runResult,
                    qrConfig,
                    cameraConfig.iso,
                    cameraConfig.exposureEv,
                )
            allResults.add(tuningResult)
        }

        // Rank results
        val ranked = diagnosticRankConfigs(allResults)

        // Save results JSON
        saveResultsJson(capture, allResults, ranked)

        withContext(Dispatchers.Main) {
            sweepProgress = 1f
            rankedResults = ranked
            sweepRunning = false
            sweepDone = true
            log("Sweep complete. Best config: ${ranked.firstOrNull()?.configId ?: "none"}")
        }
    }

    private suspend fun captureAndDecodeFrame(): FrameResult {
        val deferred = CompletableDeferred<FrameResult>()
        pendingFrame.set(deferred)
        return deferred.await()
    }

    private fun onFrameAnalyzed(
        decoded: Boolean,
        latencyMs: Float,
    ) {
        val deferred = pendingFrame.getAndSet(null)
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(
                FrameResult(
                    decoded = decoded,
                    latencyMs = latencyMs,
                    timestampNs = System.nanoTime(),
                ),
            )
        }
    }

    private fun saveResultsJson(
        capture: DiagnosticCapture,
        results: List<MobileTuningResult>,
        ranked: List<MobileScoredConfig>,
    ) {
        try {
            val json =
                JSONObject().apply {
                    put("device", profile?.deviceModel ?: "unknown")
                    put("hardware_level", profile?.hardwareLevel)
                    put("timestamp", System.currentTimeMillis())
                    put(
                        "results",
                        JSONArray().apply {
                            for (r in results) {
                                put(
                                    JSONObject().apply {
                                        put("config_id", r.cameraConfigId.toLong())
                                        put("decode_rate", r.decodeRate.toDouble())
                                        put("avg_latency_ms", r.avgLatencyMs.toDouble())
                                        put("jitter_ms", r.jitterMs.toDouble())
                                        put("thermal_events", r.thermalEvents.toLong())
                                        put("frames_total", r.framesTotal.toLong())
                                        put("frames_decoded", r.framesDecoded.toLong())
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "rankings",
                        JSONArray().apply {
                            for (r in ranked) {
                                put(
                                    JSONObject().apply {
                                        put("config_id", r.configId.toLong())
                                        put("score", r.score.toDouble())
                                    },
                                )
                            }
                        },
                    )
                }

            val sessionDir = capture.getSessionDir().parentFile ?: return
            val outFile = File(sessionDir, "results.json")
            outFile.writeText(json.toString(2))
            log("Results saved to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save results JSON", e)
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        CoroutineScope(Dispatchers.Main).launch {
            logLines.add(message)
        }
    }

    @Composable
    private fun TunerScreen() {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "QR Camera Tuner",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Device info
            profile?.let { p ->
                Text("Device: ${p.deviceModel}", fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("HW Level: ${p.hardwareLevel ?: "unknown"}", fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("ISO: ${p.isoRangeMin ?: "?"}-${p.isoRangeMax ?: "?"}", fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                sweepMatrix?.let { m ->
                    Text(
                        "Configs: ${m.cameraConfigs.size} camera x ${m.qrConfigs.size} QR",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Camera preview (small)
            if (cameraPermissionGranted) {
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
                            val imageAnalyzer =
                                ImageAnalysis
                                    .Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            cameraExecutor,
                                            TunerAnalyzer { decoded, latency ->
                                                onFrameAnalyzed(decoded, latency)
                                            },
                                        )
                                    }
                            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                            try {
                                cameraProvider.unbindAll()
                                camera =
                                    cameraProvider.bindToLifecycle(
                                        this@QrTunerActivity,
                                        cameraSelector,
                                        preview,
                                        imageAnalyzer,
                                    )
                            } catch (e: Exception) {
                                log("Camera bind error: ${e.message}")
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Controls
            if (!sweepRunning && !sweepDone && sweepMatrix != null) {
                Button(onClick = { startSweep() }) {
                    Text("Start Sweep")
                }
            }

            // Progress
            if (sweepRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { sweepProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                currentConfigId?.let {
                    Text("Config: $it", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text("Temp: ${thermalTemp}C", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            // Results
            if (sweepDone && rankedResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Results (ranked):", fontWeight = FontWeight.Bold)
                rankedResults.forEachIndexed { index, scored ->
                    val prefix = if (index == 0) ">> " else "   "
                    Text(
                        "$prefix#${index + 1} config=${scored.configId} score=${String.format("%.3f", scored.score)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Log
            Spacer(modifier = Modifier.height(12.dp))
            Text("Log:", fontWeight = FontWeight.Bold)
            for (line in logLines) {
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    /**
     * Image analyzer that decodes QR codes via ML Kit and reports results
     * back to the tuner via [onResult].
     */
    private class TunerAnalyzer(
        private val onResult: (decoded: Boolean, latencyMs: Float) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private val scanner =
            BarcodeScanning.getClient(
                com.google.mlkit.vision.barcode.BarcodeScannerOptions
                    .Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
            val startNs = System.nanoTime()
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                onResult(false, 0f)
                return
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner
                .process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val elapsed = (System.nanoTime() - startNs) / 1_000_000f
                    onResult(barcodes.isNotEmpty(), elapsed)
                }.addOnFailureListener {
                    val elapsed = (System.nanoTime() - startNs) / 1_000_000f
                    onResult(false, elapsed)
                }.addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }
}
