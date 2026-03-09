// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.content.Context
import android.util.Log
import android.util.Size
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of a single throughput measurement round.
 */
data class ThroughputResult(
    val dataSize: Int,
    val cycleMs: Int,
    val durationMs: Long,
    val totalDecodes: Int,
    val uniqueDecodes: Int,
    val totalBytesDecoded: Long,
    val ttfdMs: Long,
    val avgDecodeLatencyMs: Float,
    val effectiveBytesPerSec: Float,
    val uniqueQrsPerSec: Float,
    val decodesPerSec: Float,
)

/**
 * Measures QR throughput: how fast can we transfer data via QR codes?
 *
 * Scans with front camera (best config from tuner: 720p, zoom 1.0, ev 0)
 * and measures:
 * - Unique QR codes decoded (by sequence number in T:NNN: prefix)
 * - Total bytes decoded
 * - Effective throughput in bytes/sec
 * - TTFD (time to first decode)
 * - Decode latency per frame
 */
@ExperimentalCamera2Interop
class QrThroughputTester(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val measurementDurationMs: Long = 10_000L,
) {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[QR Throughput]"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()

    /**
     * Run a throughput measurement for the given duration.
     * The other device should be displaying QR codes (beacon mode).
     * Returns the measurement result.
     */
    suspend fun measure(onLog: (String) -> Unit): ThroughputResult {
        val totalDecodes = AtomicInteger(0)
        val uniqueSeqs = ConcurrentHashMap<String, Long>() // seq -> first decode timestamp
        val totalBytes = AtomicLong(0)
        val startTimeMs = AtomicLong(0)
        val firstDecodeMs = AtomicLong(-1)
        val latencies = mutableListOf<Long>()
        val allContents = ConcurrentHashMap<String, Int>() // content -> decode count

        val cameraProviderDeferred = CompletableDeferred<ProcessCameraProvider>()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            { cameraProviderDeferred.complete(cameraProviderFuture.get()) },
            ContextCompat.getMainExecutor(context),
        )
        val cameraProvider = cameraProviderDeferred.await()

        // Use 720p front camera — proven best config
        val resolutionSelector =
            ResolutionSelector
                .Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build()

        val imageAnalysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val startNs = System.nanoTime()

            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                barcodeScanner
                    .process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val elapsedNs = System.nanoTime() - startNs
                        synchronized(latencies) { latencies.add(elapsedNs / 1_000_000L) }

                        val qrCodes = barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }
                        for (qr in qrCodes) {
                            val content = qr.rawValue ?: continue
                            val count = totalDecodes.incrementAndGet()
                            totalBytes.addAndGet(content.length.toLong())
                            allContents[content] = (allContents[content] ?: 0) + 1

                            // Track first decode
                            if (count == 1) {
                                val st = startTimeMs.get()
                                if (st > 0) firstDecodeMs.set(System.currentTimeMillis() - st)
                            }

                            // Extract sequence from T:NNN: format
                            val seq = extractSequence(content)
                            if (seq != null && !uniqueSeqs.containsKey(seq)) {
                                uniqueSeqs[seq] = System.currentTimeMillis()
                                log("  Unique QR #${uniqueSeqs.size}: seq=$seq (${content.length}B)")
                            }
                        }
                    }.addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }

        val cameraSelector =
            CameraSelector
                .Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

        // Bind camera
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

        log("Scanning for ${measurementDurationMs / 1000}s...")

        // Wait for measurement duration
        delay(measurementDurationMs)

        // Unbind camera
        val unbindDone = CompletableDeferred<Unit>()
        ContextCompat.getMainExecutor(context).execute {
            cameraProvider.unbindAll()
            unbindDone.complete(Unit)
        }
        unbindDone.await()

        // Calculate results
        val elapsedSec = measurementDurationMs / 1000.0f
        val latencyCopy = synchronized(latencies) { latencies.toList() }
        val avgLatency = if (latencyCopy.isNotEmpty()) latencyCopy.average().toFloat() else 0f

        // Determine data size from decoded content
        val sampleContent = allContents.keys.firstOrNull()
        val dataSize = sampleContent?.length ?: 0

        val result =
            ThroughputResult(
                dataSize = dataSize,
                cycleMs = 0, // filled in by caller
                durationMs = measurementDurationMs,
                totalDecodes = totalDecodes.get(),
                uniqueDecodes = uniqueSeqs.size,
                totalBytesDecoded = totalBytes.get(),
                ttfdMs = firstDecodeMs.get(),
                avgDecodeLatencyMs = avgLatency,
                effectiveBytesPerSec = if (elapsedSec > 0) totalBytes.get() / elapsedSec else 0f,
                uniqueQrsPerSec = if (elapsedSec > 0) uniqueSeqs.size / elapsedSec else 0f,
                decodesPerSec = if (elapsedSec > 0) totalDecodes.get() / elapsedSec else 0f,
            )

        log("--- Results ---")
        log("Duration: ${measurementDurationMs}ms")
        log("TTFD: ${result.ttfdMs}ms")
        log("Total decodes: ${result.totalDecodes} (${"%.1f".format(result.decodesPerSec)}/s)")
        log("Unique QRs: ${result.uniqueDecodes} (${"%.2f".format(result.uniqueQrsPerSec)}/s)")
        log("Data size per QR: ${result.dataSize}B")
        log("Total bytes: ${result.totalBytesDecoded}")
        log("THROUGHPUT: ${"%.0f".format(result.effectiveBytesPerSec)} B/s")
        log("Avg decode latency: ${"%.1f".format(result.avgDecodeLatencyMs)}ms")

        return result
    }

    private fun extractSequence(content: String): String? {
        // Format: T:NNN:payload
        if (!content.startsWith("T:")) return null
        val secondColon = content.indexOf(':', 2)
        if (secondColon < 0) return null
        return content.substring(2, secondColon)
    }

    fun saveResultsJson(results: List<ThroughputResult>): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val timestamp =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
        val file = File(dir, "qr-throughput-$timestamp.json")

        val jsonArray = JSONArray()
        for (r in results) {
            val obj = JSONObject()
            obj.put("data_size", r.dataSize)
            obj.put("cycle_ms", r.cycleMs)
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
        root.put("results", jsonArray)

        file.writeText(root.toString(2))
        log("Results saved: ${file.absolutePath}")
        return file
    }

    fun release() {
        barcodeScanner.close()
        analysisExecutor.shutdown()
    }

    private fun log(msg: String) {
        Log.i(TAG, "$LOG_PREFIX $msg")
    }
}
