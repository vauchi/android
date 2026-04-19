// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures QR throughput using the Rust rxing/rqrr scanner via UniFFI.
 *
 * Unlike [QrThroughputTester] which uses ML Kit, this tester:
 * - Uses the same scanner backend as the production exchange
 * - Supports configurable camera resolution (240p, 480p, 720p)
 * - Extracts Y-plane luma data and passes it to [RustScannerBridge]
 *
 * Launch via QrTunerActivity:
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrTunerActivity \
 *     --es test rxing-throughput --ei duration 15 --es resolution 240p
 */
@ExperimentalCamera2Interop
class RxingThroughputTester(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val measurementDurationMs: Long = 15_000L,
    private val resolution: String = "240p",
    private val scannerMode: ScannerMode = ScannerMode.RqrrRaw,
    private val useFrontCamera: Boolean = true,
) {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[rxing Throughput]"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Run a throughput measurement for the given duration.
     * The other device should be displaying QR codes (beacon mode).
     */
    @OptIn(ExperimentalGetImage::class)
    suspend fun measure(onLog: (String) -> Unit): ThroughputResult {
        val totalDecodes = AtomicInteger(0)
        val uniqueSeqs = ConcurrentHashMap<String, Long>()
        val totalBytes = AtomicLong(0)
        val totalFrames = AtomicInteger(0)
        val startTimeMs = AtomicLong(0)
        val firstDecodeMs = AtomicLong(-1)
        val latencies = mutableListOf<Long>()

        val cameraProviderDeferred = CompletableDeferred<ProcessCameraProvider>()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            { cameraProviderDeferred.complete(cameraProviderFuture.get()) },
            ContextCompat.getMainExecutor(context),
        )
        val cameraProvider = cameraProviderDeferred.await()

        val targetSize =
            when (resolution) {
                "240p" -> Size(320, 240)
                "480p" -> Size(640, 480)
                "720p" -> Size(1280, 720)
                else -> Size(320, 240)
            }

        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build()

        val imageAnalysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            totalFrames.incrementAndGet()
            val startNs = System.nanoTime()

            try {
                val plane = imageProxy.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val width = imageProxy.width
                val height = imageProxy.height

                // Extract Y-plane luma data
                val lumaData =
                    if (rowStride == width) {
                        val data = ByteArray(width * height)
                        buffer.get(data)
                        data
                    } else {
                        // Handle row stride padding
                        val data = ByteArray(width * height)
                        for (row in 0 until height) {
                            buffer.position(row * rowStride)
                            buffer.get(data, row * width, width)
                        }
                        data
                    }

                val decoded = RustScannerBridge.scan(scannerMode, lumaData, width, height)
                val elapsedNs = System.nanoTime() - startNs
                synchronized(latencies) { latencies.add(elapsedNs / 1_000_000L) }

                if (decoded != null) {
                    val count = totalDecodes.incrementAndGet()
                    totalBytes.addAndGet(decoded.length.toLong())

                    if (count == 1) {
                        val st = startTimeMs.get()
                        if (st > 0) firstDecodeMs.set(System.currentTimeMillis() - st)
                    }

                    val seq = extractSequence(decoded)
                    if (seq != null && !uniqueSeqs.containsKey(seq)) {
                        uniqueSeqs[seq] = System.currentTimeMillis()
                    }
                }
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector =
            CameraSelector
                .Builder()
                .requireLensFacing(
                    if (useFrontCamera) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    },
                ).build()

        val cameraDeferred = CompletableDeferred<Unit>()
        ContextCompat.getMainExecutor(context).execute {
            try {
                cameraProvider.unbindAll()
                startTimeMs.set(System.currentTimeMillis())
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                cameraDeferred.complete(Unit)
            } catch (e: Exception) {
                log("Failed to bind camera: ${e.message}")
                cameraDeferred.complete(Unit)
            }
        }
        cameraDeferred.await()

        log("Scanning at $resolution with ${scannerMode.name} for ${measurementDurationMs / 1000}s...")

        delay(measurementDurationMs)

        val unbindDone = CompletableDeferred<Unit>()
        ContextCompat.getMainExecutor(context).execute {
            cameraProvider.unbindAll()
            unbindDone.complete(Unit)
        }
        unbindDone.await()

        val elapsedSec = measurementDurationMs / 1000.0f
        val latencyCopy = synchronized(latencies) { latencies.toList() }
        val avgLatency = if (latencyCopy.isNotEmpty()) latencyCopy.average().toFloat() else 0f
        val frames = totalFrames.get()
        val decodes = totalDecodes.get()
        val decodeRate = if (frames > 0) decodes.toFloat() / frames else 0f

        val result =
            ThroughputResult(
                dataSize = 0,
                cycleMs = 0,
                durationMs = measurementDurationMs,
                totalDecodes = decodes,
                uniqueDecodes = uniqueSeqs.size,
                totalBytesDecoded = totalBytes.get(),
                ttfdMs = firstDecodeMs.get(),
                avgDecodeLatencyMs = avgLatency,
                effectiveBytesPerSec = if (elapsedSec > 0) totalBytes.get() / elapsedSec else 0f,
                uniqueQrsPerSec = if (elapsedSec > 0) uniqueSeqs.size / elapsedSec else 0f,
                decodesPerSec = if (elapsedSec > 0) decodes / elapsedSec else 0f,
            )

        log("--- Results ---")
        log("Resolution: $resolution | Scanner: ${scannerMode.name}")
        log("Camera: ${if (useFrontCamera) "front" else "rear"}")
        log("Frames processed: $frames (${"%.1f".format(frames / elapsedSec)} fps)")
        log("Decode rate: ${"%.1f".format(decodeRate * 100)}% ($decodes/$frames)")
        log("TTFD: ${result.ttfdMs}ms")
        log("Unique QRs: ${result.uniqueDecodes} (${"%.2f".format(result.uniqueQrsPerSec)}/s)")
        log("THROUGHPUT: ${"%.0f".format(result.effectiveBytesPerSec)} B/s")
        log("Avg decode latency: ${"%.1f".format(result.avgDecodeLatencyMs)}ms")

        return result
    }

    fun saveResultsJson(results: List<ThroughputResult>): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val timestamp =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
        val file = File(dir, "rxing-throughput-$timestamp.json")

        val jsonArray = JSONArray()
        for (r in results) {
            val obj = JSONObject()
            obj.put("resolution", resolution)
            obj.put("scanner", scannerMode.name)
            obj.put("camera", if (useFrontCamera) "front" else "rear")
            obj.put("data_size", r.dataSize)
            obj.put("duration_ms", r.durationMs)
            obj.put("total_decodes", r.totalDecodes)
            obj.put("unique_decodes", r.uniqueDecodes)
            obj.put("total_bytes", r.totalBytesDecoded)
            obj.put("ttfd_ms", r.ttfdMs)
            obj.put("avg_latency_ms", r.avgDecodeLatencyMs.toDouble())
            obj.put("bytes_per_sec", r.effectiveBytesPerSec.toDouble())
            obj.put("unique_qrs_per_sec", r.uniqueQrsPerSec.toDouble())
            obj.put("decodes_per_sec", r.decodesPerSec.toDouble())
            jsonArray.put(obj)
        }

        val root = JSONObject()
        root.put("timestamp", timestamp)
        root.put("device", android.os.Build.MODEL)
        root.put("android_version", android.os.Build.VERSION.SDK_INT)
        root.put("resolution", resolution)
        root.put("scanner", scannerMode.name)
        root.put("results", jsonArray)

        file.writeText(root.toString(2))
        log("Results saved: ${file.absolutePath}")
        return file
    }

    fun release() {
        analysisExecutor.shutdown()
    }

    private fun extractSequence(content: String): String? {
        if (!content.startsWith("T:")) return null
        val secondColon = content.indexOf(':', 2)
        if (secondColon < 0) return null
        return content.substring(2, secondColon)
    }

    private fun log(msg: String) {
        Log.i(TAG, "$LOG_PREFIX $msg")
    }
}
