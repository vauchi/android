// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera configuration for QR sweep testing.
 * Self-contained — no UniFFI dependency.
 */
data class CameraConfig(
    val id: Int,
    val useFrontCamera: Boolean,
    val resolution: String,
    val zoomRatio: Float,
    val exposureEv: Int,
)

/**
 * Result of testing a single camera configuration.
 */
data class ConfigResult(
    val config: CameraConfig,
    val framesTotal: Int,
    val framesDecoded: Int,
    val avgLatencyMs: Float,
    val jitterMs: Float,
    val score: Float,
    val timeToFirstDecodeMs: Long = -1, // -1 means never decoded
)

/**
 * Generates sweep matrices and runs camera configuration tests using
 * CameraX + Camera2 interop + ML Kit barcode scanning.
 */
class CameraConfigTuner(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val stabilizationMs: Long = DEFAULT_STABILIZATION_MS,
    val useZxing: Boolean = false,
) {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[QR Tuner]"
        private const val FRAMES_PER_CONFIG = 30
        private const val DEFAULT_STABILIZATION_MS = 1500L
        private const val FRAME_TIMEOUT_MS = 10_000L
    }

    private val thermalMonitor = ThermalMonitor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()
    private val zxingReader =
        if (useZxing) {
            com.google.zxing.MultiFormatReader().apply {
                setHints(
                    mapOf(
                        com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                            listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                        com.google.zxing.DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
        } else {
            null
        }

    /**
     * Generates the full sweep matrix: 2 cameras x 2 resolutions x 3 zooms x 3 EVs = 36 configs.
     */
    fun generateFullSweepMatrix(): List<CameraConfig> {
        val configs = mutableListOf<CameraConfig>()
        var id = 0
        for (front in listOf(false, true)) {
            for (res in listOf("720p", "1080p")) {
                for (zoom in listOf(1.0f, 1.5f, 2.0f)) {
                    for (ev in listOf(-1, 0, 1)) {
                        configs.add(
                            CameraConfig(
                                id = id++,
                                useFrontCamera = front,
                                resolution = res,
                                zoomRatio = zoom,
                                exposureEv = ev,
                            ),
                        )
                    }
                }
            }
        }
        return configs
    }

    /**
     * Generates a quick sweep matrix: 5 representative configs.
     */
    fun generateQuickSweepMatrix(): List<CameraConfig> =
        listOf(
            CameraConfig(id = 0, useFrontCamera = true, resolution = "720p", zoomRatio = 1.0f, exposureEv = 0),
            CameraConfig(id = 1, useFrontCamera = true, resolution = "1080p", zoomRatio = 1.0f, exposureEv = 0),
            CameraConfig(id = 2, useFrontCamera = false, resolution = "720p", zoomRatio = 1.0f, exposureEv = 0),
            CameraConfig(id = 3, useFrontCamera = false, resolution = "1080p", zoomRatio = 1.0f, exposureEv = 0),
            CameraConfig(id = 4, useFrontCamera = false, resolution = "1080p", zoomRatio = 2.0f, exposureEv = 0),
        )

    /**
     * Generates a front-camera-only sweep: 4 resolutions x 4 zooms x 3 EVs = 48 configs.
     * Covers real-world distance variation (zoom simulates near/far).
     */
    fun generateFrontSweepMatrix(): List<CameraConfig> {
        val configs = mutableListOf<CameraConfig>()
        var id = 0
        for (res in listOf("480p", "720p", "1080p")) {
            for (zoom in listOf(1.0f, 1.5f, 2.0f, 3.0f)) {
                for (ev in listOf(-1, 0, 1)) {
                    configs.add(
                        CameraConfig(
                            id = id++,
                            useFrontCamera = true,
                            resolution = res,
                            zoomRatio = zoom,
                            exposureEv = ev,
                        ),
                    )
                }
            }
        }
        return configs
    }

    /**
     * Filters configs to only front or rear camera.
     */
    fun filterByCamera(
        configs: List<CameraConfig>,
        front: Boolean,
    ): List<CameraConfig> =
        configs
            .filter { it.useFrontCamera == front }
            .mapIndexed { i, c -> c.copy(id = i) }

    /**
     * Runs the sweep for a list of configs. Returns ordered results.
     * Caller must invoke this from a coroutine on a suitable dispatcher.
     */
    suspend fun runSweep(
        configs: List<CameraConfig>,
        onProgress: (current: Int, total: Int, result: ConfigResult) -> Unit,
    ): List<ConfigResult> {
        val results = mutableListOf<ConfigResult>()
        for ((index, config) in configs.withIndex()) {
            // Thermal check between configs
            if (thermalMonitor.isCritical()) {
                log("Thermal throttle detected (${thermalMonitor.getTemperatureCelsius()}C), cooling down...")
                thermalMonitor.waitForCooldown()
            }

            val result = testConfig(config)
            results.add(result)
            onProgress(index + 1, configs.size, result)
        }
        return results.sortedByDescending { it.score }
    }

    /**
     * Tests a single camera configuration.
     * Binds camera, applies settings via Camera2 interop, captures frames,
     * measures decode rate and latency.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun testConfig(config: CameraConfig): ConfigResult {
        val cameraFace = if (config.useFrontCamera) "front" else "rear"
        log("Testing config ${config.id}: camera=$cameraFace res=${config.resolution} zoom=${config.zoomRatio} ev=${config.exposureEv}")

        val framesDecoded = AtomicInteger(0)
        val framesTotal = AtomicInteger(0)
        val totalLatencyNs = AtomicLong(0)
        val latencies = mutableListOf<Long>()
        val cameraStartTimeMs = AtomicLong(0) // set when camera binds
        val firstDecodeTimeMs = AtomicLong(-1) // set on first QR decode

        val targetSize =
            when (config.resolution) {
                "480p" -> Size(640, 480)
                "720p" -> Size(1280, 720)
                "1080p" -> Size(1920, 1080)
                else -> Size(1280, 720)
            }

        val cameraProviderDeferred = CompletableDeferred<ProcessCameraProvider>()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            { cameraProviderDeferred.complete(cameraProviderFuture.get()) },
            ContextCompat.getMainExecutor(context),
        )

        val cameraProvider = cameraProviderDeferred.await()

        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build()

        val imageAnalysisBuilder =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // Apply Camera2 interop settings for EV compensation
        val camera2Extender = Camera2Interop.Extender(imageAnalysisBuilder)
        camera2Extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            config.exposureEv,
        )

        val imageAnalysis = imageAnalysisBuilder.build()

        val analysisComplete = CompletableDeferred<Unit>()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val frameIndex = framesTotal.incrementAndGet()
            if (frameIndex > FRAMES_PER_CONFIG) {
                imageProxy.close()
                if (!analysisComplete.isCompleted) {
                    analysisComplete.complete(Unit)
                }
                return@setAnalyzer
            }

            val startNs = System.nanoTime()

            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                if (useZxing && zxingReader != null) {
                    // ZXing path: synchronous decode from Y plane
                    try {
                        val yPlane = mediaImage.planes[0]
                        val width = mediaImage.width
                        val height = mediaImage.height
                        val rowStride = yPlane.rowStride
                        // Copy Y plane accounting for row stride padding
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
                        val source =
                            com.google.zxing.PlanarYUVLuminanceSource(
                                bytes,
                                width,
                                height,
                                0,
                                0,
                                width,
                                height,
                                false,
                            )
                        val binaryBitmap =
                            com.google.zxing.BinaryBitmap(
                                com.google.zxing.common
                                    .HybridBinarizer(source),
                            )
                        val result = zxingReader.decodeWithState(binaryBitmap)
                        val elapsedNs = System.nanoTime() - startNs
                        val elapsedMs = elapsedNs / 1_000_000L
                        framesDecoded.incrementAndGet().let { decoded ->
                            if (decoded == 1) {
                                val startMs = cameraStartTimeMs.get()
                                if (startMs > 0) firstDecodeTimeMs.set(System.currentTimeMillis() - startMs)
                                val preview = result.text?.take(50)?.let { if (result.text.length > 50) "$it..." else it } ?: "(no content)"
                                log("  -> ZXing decoded (${firstDecodeTimeMs.get()}ms): $preview")
                            }
                        }
                        synchronized(latencies) { latencies.add(elapsedMs) }
                        totalLatencyNs.addAndGet(elapsedNs)
                    } catch (_: com.google.zxing.NotFoundException) {
                        val elapsedNs = System.nanoTime() - startNs
                        synchronized(latencies) { latencies.add(elapsedNs / 1_000_000L) }
                        totalLatencyNs.addAndGet(elapsedNs)
                    } catch (e: Exception) {
                        val elapsedNs = System.nanoTime() - startNs
                        synchronized(latencies) { latencies.add(elapsedNs / 1_000_000L) }
                        totalLatencyNs.addAndGet(elapsedNs)
                    } finally {
                        zxingReader.reset()
                    }
                    imageProxy.close()
                    if (frameIndex >= FRAMES_PER_CONFIG && !analysisComplete.isCompleted) {
                        analysisComplete.complete(Unit)
                    }
                } else {
                    // ML Kit path: async decode
                    val inputImage =
                        InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees,
                        )
                    barcodeScanner
                        .process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            val elapsedNs = System.nanoTime() - startNs
                            val elapsedMs = elapsedNs / 1_000_000L
                            val qrCodes = barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }
                            if (qrCodes.isNotEmpty()) {
                                val decoded = framesDecoded.incrementAndGet()
                                if (decoded == 1) {
                                    val startMs = cameraStartTimeMs.get()
                                    if (startMs > 0) firstDecodeTimeMs.set(System.currentTimeMillis() - startMs)
                                    val content = qrCodes.first().rawValue ?: "(no content)"
                                    val preview = if (content.length > 50) content.take(50) + "..." else content
                                    log("  -> MLKit decoded (${firstDecodeTimeMs.get()}ms): $preview")
                                }
                            }
                            synchronized(latencies) { latencies.add(elapsedMs) }
                            totalLatencyNs.addAndGet(elapsedNs)
                        }.addOnCompleteListener {
                            imageProxy.close()
                            if (frameIndex >= FRAMES_PER_CONFIG && !analysisComplete.isCompleted) {
                                analysisComplete.complete(Unit)
                            }
                        }
                }
            } else {
                imageProxy.close()
                if (frameIndex >= FRAMES_PER_CONFIG && !analysisComplete.isCompleted) {
                    analysisComplete.complete(Unit)
                }
            }
        }

        val cameraSelector =
            CameraSelector
                .Builder()
                .requireLensFacing(
                    if (config.useFrontCamera) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    },
                ).build()

        // Bind on main thread
        val cameraDeferred = CompletableDeferred<androidx.camera.core.Camera?>()
        ContextCompat.getMainExecutor(context).execute {
            try {
                cameraProvider.unbindAll()
                cameraStartTimeMs.set(System.currentTimeMillis())
                val camera =
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageAnalysis,
                    )
                cameraDeferred.complete(camera)
            } catch (e: Exception) {
                log("Failed to bind camera for config ${config.id}: ${e.message}")
                cameraDeferred.complete(null)
            }
        }

        val camera = cameraDeferred.await()
        if (camera == null) {
            return ConfigResult(
                config = config,
                framesTotal = 0,
                framesDecoded = 0,
                avgLatencyMs = 0f,
                jitterMs = 0f,
                score = 0f,
            )
        }

        // Apply zoom
        camera.cameraControl.setZoomRatio(config.zoomRatio)

        // Stabilization delay (configurable, default 1500ms)
        delay(stabilizationMs)

        // Wait for frame capture to complete
        try {
            withTimeout(FRAME_TIMEOUT_MS) {
                analysisComplete.await()
            }
        } catch (_: Exception) {
            log("Timeout waiting for frames on config ${config.id}")
        }

        // Small delay to let final ML Kit callbacks complete
        delay(300)

        // Unbind on main thread
        val unbindDone = CompletableDeferred<Unit>()
        ContextCompat.getMainExecutor(context).execute {
            cameraProvider.unbindAll()
            unbindDone.complete(Unit)
        }
        unbindDone.await()

        val totalFrames = framesTotal.get().coerceAtMost(FRAMES_PER_CONFIG)
        val decoded = framesDecoded.get()
        val latencyCopy = synchronized(latencies) { latencies.toList() }

        val avgLatency = if (latencyCopy.isNotEmpty()) latencyCopy.average().toFloat() else 0f
        val jitter =
            if (latencyCopy.size > 1) {
                val mean = latencyCopy.average()
                val variance = latencyCopy.map { (it - mean) * (it - mean) }.average()
                kotlin.math.sqrt(variance).toFloat()
            } else {
                0f
            }

        val decodeRate = if (totalFrames > 0) decoded.toFloat() / totalFrames else 0f
        // Normalize latency: assume 200ms is worst acceptable, 0ms is best
        val normalizedLatency = (avgLatency / 200f).coerceIn(0f, 1f)
        val score = decodeRate * 0.7f + (1f - normalizedLatency) * 0.3f

        return ConfigResult(
            config = config,
            framesTotal = totalFrames,
            framesDecoded = decoded,
            avgLatencyMs = avgLatency,
            jitterMs = jitter,
            score = score,
            timeToFirstDecodeMs = firstDecodeTimeMs.get(),
        )
    }

    /**
     * Ranks results and returns the best configuration.
     */
    fun rankResults(results: List<ConfigResult>): ConfigResult? = results.maxByOrNull { it.score }

    /**
     * Saves results as JSON to app external files directory.
     */
    fun saveResultsJson(results: List<ConfigResult>): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val timestamp =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
        val file = File(dir, "qr-tuner-$timestamp.json")

        val jsonArray = JSONArray()
        for (result in results) {
            val obj = JSONObject()
            obj.put("config_id", result.config.id)
            obj.put("camera", if (result.config.useFrontCamera) "front" else "rear")
            obj.put("resolution", result.config.resolution)
            obj.put("zoom", result.config.zoomRatio.toDouble())
            obj.put("ev", result.config.exposureEv)
            obj.put("frames_total", result.framesTotal)
            obj.put("frames_decoded", result.framesDecoded)
            obj.put(
                "decode_rate_pct",
                if (result.framesTotal > 0) {
                    (result.framesDecoded.toFloat() / result.framesTotal * 100).toDouble()
                } else {
                    0.0
                },
            )
            obj.put("avg_latency_ms", result.avgLatencyMs.toDouble())
            obj.put("jitter_ms", result.jitterMs.toDouble())
            obj.put("time_to_first_decode_ms", result.timeToFirstDecodeMs)
            obj.put("score", result.score.toDouble())
            jsonArray.put(obj)
        }

        val root = JSONObject()
        root.put("timestamp", timestamp)
        root.put("device", android.os.Build.MODEL)
        root.put("android_version", android.os.Build.VERSION.SDK_INT)
        root.put("results", jsonArray)

        file.writeText(root.toString(2))
        log("Results saved to ${file.absolutePath}")
        return file
    }

    /**
     * Releases resources.
     */
    fun release() {
        barcodeScanner.close()
        analysisExecutor.shutdown()
    }

    private fun log(msg: String) {
        Log.i(TAG, "$LOG_PREFIX $msg")
    }
}
