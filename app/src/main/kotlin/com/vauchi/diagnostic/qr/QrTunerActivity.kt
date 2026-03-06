// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vauchi.ui.theme.VauchiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QR Camera Tuner activity that sweeps camera configurations on real devices
 * to find optimal QR reading settings.
 *
 * Launch via ADB:
 *   adb shell am start -n com.vauchi/.diagnostic.qr.QrTunerActivity --es test sweep
 *   adb shell am start -n com.vauchi/.diagnostic.qr.QrTunerActivity --es test quick
 *   adb shell am start -n com.vauchi/.diagnostic.qr.QrTunerActivity  (interactive)
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class QrTunerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[QR Tuner]"
    }

    private val logLines = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var cameraGranted by mutableStateOf(false)
    private var showQrOverlay by mutableStateOf(false)
    private var qrBitmap by mutableStateOf<Bitmap?>(null)

    private var tuner: CameraConfigTuner? = null

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            if (granted) {
                // If we had a pending auto-test, re-trigger
                pendingTest?.let { test ->
                    pendingTest = null
                    startSweep(test)
                }
            } else {
                log("Camera permission denied")
            }
        }

    private var pendingTest: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        setContent {
            VauchiTheme {
                QrTunerScreen(
                    logLines = logLines,
                    running = running,
                    progress = progress,
                    cameraGranted = cameraGranted,
                    qrOverlay = if (showQrOverlay) qrBitmap else null,
                    onBack = { finish() },
                    onStartSweep = { startSweep("sweep") },
                    onStartQuick = { startSweep("quick") },
                    onStartFront = { startSweep("front") },
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        tuner?.release()
    }

    private fun handleIntent(intent: Intent?) {
        val testName = intent?.getStringExtra("test") ?: return
        val mode = intent?.getStringExtra("mode") // "dual" = show QR while scanning
        if (mode == "dual") {
            showQrOverlay = true
            qrBitmap = generateQrBitmap("wb://BIDIRECTIONAL_TEST_${android.os.Build.MODEL}_${System.currentTimeMillis()}")
            log("DUAL MODE: Showing QR on screen while scanning")
        }
        if (!cameraGranted) {
            pendingTest = testName
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        startSweep(testName)
    }

    private fun generateQrBitmap(data: String): Bitmap {
        val writer = QRCodeWriter()
        val size = 600
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    private fun startSweep(testName: String) {
        if (running) return
        running = true
        progress = 0f
        logLines.clear()

        val t = CameraConfigTuner(context = this, lifecycleOwner = this)
        tuner = t

        val cameraFilter = intent?.getStringExtra("camera") // "front" or "rear"

        var configs =
            when (testName) {
                "front" -> {
                    log("Starting FRONT-ONLY sweep (18 configs)")
                    t.generateFrontSweepMatrix()
                }

                "quick" -> {
                    log("Starting QUICK sweep (5 configs)")
                    t.generateQuickSweepMatrix()
                }

                "sweep" -> {
                    log("Starting FULL sweep (36 configs)")
                    t.generateFullSweepMatrix()
                }

                else -> {
                    log("Unknown test '$testName', defaulting to quick sweep")
                    t.generateQuickSweepMatrix()
                }
            }

        // Apply camera filter if specified
        if (cameraFilter == "front") {
            configs = t.filterByCamera(configs, front = true)
            log("Filtered to FRONT camera only: ${configs.size} configs")
        } else if (cameraFilter == "rear") {
            configs = t.filterByCamera(configs, front = false)
            log("Filtered to REAR camera only: ${configs.size} configs")
        }

        log("Device: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.SDK_INT})")
        log("Configs to test: ${configs.size}")
        log("---")

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val results =
                    t.runSweep(configs) { current, total, result ->
                        val cameraFace = if (result.config.useFrontCamera) "front" else "rear"
                        val decodeRate =
                            if (result.framesTotal > 0) {
                                (result.framesDecoded.toFloat() / result.framesTotal * 100)
                            } else {
                                0f
                            }

                        val ttfd = if (result.timeToFirstDecodeMs >= 0) "${result.timeToFirstDecodeMs}ms" else "never"
                        val msg =
                            "Config ${result.config.id}: " +
                                "camera=$cameraFace " +
                                "res=${result.config.resolution} " +
                                "zoom=${result.config.zoomRatio} " +
                                "ev=${result.config.exposureEv} " +
                                "-> decode=${"%.0f".format(decodeRate)}% " +
                                "latency=${"%.1f".format(result.avgLatencyMs)}ms " +
                                "ttfd=$ttfd"
                        log(msg)

                        runOnUiThread {
                            progress = current.toFloat() / total
                        }
                    }

                log("---")
                log("Sweep complete. ${results.size} configs tested.")

                // Log ranking
                val ranked = results.sortedByDescending { it.score }
                log("")
                log("=== TOP 5 ===")
                for ((i, r) in ranked.take(5).withIndex()) {
                    val face = if (r.config.useFrontCamera) "front" else "rear"
                    val rate =
                        if (r.framesTotal > 0) {
                            (r.framesDecoded.toFloat() / r.framesTotal * 100)
                        } else {
                            0f
                        }
                    val ttfd = if (r.timeToFirstDecodeMs >= 0) "${r.timeToFirstDecodeMs}ms" else "never"
                    log(
                        "#${i + 1}: config=${r.config.id} score=${"%.3f".format(r.score)} " +
                            "camera=$face res=${r.config.resolution} " +
                            "zoom=${r.config.zoomRatio} ev=${r.config.exposureEv} " +
                            "decode=${"%.0f".format(rate)}% latency=${"%.1f".format(r.avgLatencyMs)}ms " +
                            "ttfd=$ttfd",
                    )
                }

                // Log best
                val best = t.rankResults(results)
                if (best != null) {
                    val face = if (best.config.useFrontCamera) "front" else "rear"
                    log("")
                    log(
                        "BEST: config=${best.config.id} score=${"%.3f".format(best.score)} " +
                            "camera=$face res=${best.config.resolution} " +
                            "zoom=${best.config.zoomRatio} ev=${best.config.exposureEv}",
                    )
                }

                // Save JSON
                val file = t.saveResultsJson(ranked)
                if (file != null) {
                    log("JSON saved: ${file.name}")
                }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    running = false
                    progress = 1f
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, "$LOG_PREFIX $msg")
        logLines.add(msg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrTunerScreen(
    logLines: List<String>,
    running: Boolean,
    progress: Float,
    cameraGranted: Boolean,
    qrOverlay: Bitmap? = null,
    onBack: () -> Unit,
    onStartSweep: () -> Unit,
    onStartQuick: () -> Unit,
    onStartFront: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR Camera Tuner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    .padding(16.dp),
        ) {
            if (!cameraGranted) {
                Text(
                    text = "Camera permission required",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!running) {
                Button(
                    onClick = onStartSweep,
                    enabled = cameraGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start Full Sweep (36 configs)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onStartQuick,
                    enabled = cameraGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Quick Test (5 configs)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onStartFront,
                    enabled = cameraGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Front Camera Sweep (18 configs)")
                }
            }

            if (running) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Testing... ${"%.0f".format(progress * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // QR overlay for dual mode (simulates showing your own QR while scanning)
            if (qrOverlay != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qrOverlay.asImageBitmap(),
                        contentDescription = "QR Code overlay",
                        modifier =
                            Modifier
                                .fillMaxWidth(0.6f)
                                .aspectRatio(1f),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                for (line in logLines) {
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight =
                            when {
                                line.startsWith("BEST:") -> FontWeight.Bold
                                line.startsWith("#") -> FontWeight.Medium
                                else -> FontWeight.Normal
                            },
                        color =
                            when {
                                line.startsWith("ERROR") -> MaterialTheme.colorScheme.error
                                line.startsWith("BEST:") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        }
    }
}
