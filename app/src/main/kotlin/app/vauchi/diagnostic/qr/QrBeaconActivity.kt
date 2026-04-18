// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.vauchi.ui.theme.VauchiTheme
import app.vauchi.util.generateQrBitmap
import kotlinx.coroutines.delay
import uniffi.vauchi_platform.MobileErrorCorrectionLevel

/**
 * QR Beacon: displays QR codes on screen for another device to scan.
 *
 * Modes:
 *   static     — one QR, fixed data size, shown indefinitely
 *   cycle      — sequential QR codes, cycling at configurable interval
 *   throughput — pre-generated frame sequence at configurable fps for bulk transfer benchmarks
 *
 * Launch via ADB:
 *   # Static (default)
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --ei data_size 200 --ei cycle_ms 0 --ei count 1
 *
 *   # Cycle mode (legacy)
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --ei data_size 100 --ei cycle_ms 500 --ei count 20
 *
 *   # Throughput mode with payload preset
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --es payload_size large --es mode throughput --ei fps 10
 *
 *   # Throughput mode with custom total
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --es payload_size xlarge --es mode throughput --ei fps 5 --ei total_kb 100
 *
 * Payload size presets (--es payload_size):
 *   small  = 200 bytes  (~ Version 5)
 *   medium = 600 bytes  (~ Version 10)
 *   large  = 1400 bytes (~ Version 20)  — default for throughput mode
 *   xlarge = 2400 bytes (~ Version 30)
 *   max    = 3300 bytes (~ Version 40)
 *
 * QR payload format: T:<seq_num>:<padded_data>
 * - seq_num: 3-digit zero-padded sequence (001, 002, ...)
 * - padded_data: deterministic payload to reach target data_size
 *
 * large/xlarge/max presets use ECC-L for maximum capacity.
 */
class QrBeaconActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[QR Beacon]"
        private const val QR_RENDER_SIZE = 800

        /** Payload size presets mapping to approximate QR version capacities. */
        private val PAYLOAD_PRESETS =
            mapOf(
                "small" to 200,
                "medium" to 600,
                "large" to 1400,
                "xlarge" to 2400,
                "max" to 3300,
            )

        /** Presets that require low error correction for the data to fit. */
        private val LOW_ECC_PRESETS = setOf("large", "xlarge", "max")
    }

    private var currentBitmap by mutableStateOf<Bitmap?>(null)
    private var statusText by mutableStateOf("")
    private var currentSeq by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent?.getStringExtra("mode") ?: "default"
        val payloadPreset = intent?.getStringExtra("payload_size")
        val bgMode = intent?.getStringExtra("bg") ?: "bw"

        if (mode == "throughput") {
            startThroughputMode(payloadPreset, bgMode)
        } else {
            startLegacyMode(payloadPreset, bgMode)
        }
    }

    /** Static / cycle mode — backwards-compatible with existing ADB params. */
    private fun startLegacyMode(
        payloadPreset: String?,
        bgMode: String,
    ) {
        val dataSize = resolveDataSize(payloadPreset, intent?.getIntExtra("data_size", 200) ?: 200)
        val forceLowEcc = payloadPreset != null && payloadPreset in LOW_ECC_PRESETS
        val cycleMs = intent?.getIntExtra("cycle_ms", 0) ?: 0
        val count = intent?.getIntExtra("count", 1) ?: 1

        logBrightness()
        log("Starting beacon: data_size=$dataSize cycle_ms=$cycleMs count=$count bg=$bgMode preset=$payloadPreset")

        val bitmaps = generateBitmaps(count, dataSize, forceLowEcc, bgMode)

        if (bitmaps.isEmpty()) {
            statusText = "ERROR: Could not generate QR codes for size=$dataSize"
            setContent { VauchiTheme { BeaconScreen(null, statusText, currentSeq, bgMode == "inverted") } }
            return
        }

        currentBitmap = bitmaps[0]
        currentSeq = 1
        statusText =
            if (cycleMs == 0) {
                "STATIC: ${dataSize}B [$bgMode] | seq=1"
            } else {
                "CYCLING: ${dataSize}B x $count [$bgMode] @ ${cycleMs}ms"
            }

        setContent {
            VauchiTheme {
                if (cycleMs > 0 && bitmaps.size > 1) {
                    LaunchedEffect(Unit) {
                        var idx = 0
                        while (true) {
                            currentBitmap = bitmaps[idx]
                            currentSeq = idx + 1
                            statusText = "CYCLING: ${dataSize}B | seq=${idx + 1}/$count @ ${cycleMs}ms"
                            idx = (idx + 1) % bitmaps.size
                            delay(cycleMs.toLong())
                        }
                    }
                }
                BeaconScreen(currentBitmap, statusText, currentSeq, bgMode == "inverted")
            }
        }
    }

    /** Throughput mode — pre-generates all frames, cycles at target fps. */
    private fun startThroughputMode(
        payloadPreset: String?,
        bgMode: String,
    ) {
        val preset = payloadPreset ?: "large"
        val frameSize = resolveDataSize(preset, PAYLOAD_PRESETS["large"]!!)
        val forceLowEcc = preset in LOW_ECC_PRESETS
        val fps = (intent?.getIntExtra("fps", 10) ?: 10).coerceIn(1, 60)
        val totalKb = (intent?.getIntExtra("total_kb", 50) ?: 50).coerceIn(1, 10_000)
        val totalBytes = totalKb * 1024
        val frameCount = (totalBytes + frameSize - 1) / frameSize // ceil division
        val frameDelayMs = 1000L / fps

        logBrightness()
        log("Throughput mode: preset=$preset frame=${frameSize}B fps=$fps total=${totalKb}KB frames=$frameCount")

        val bitmaps = generateBitmaps(frameCount, frameSize, forceLowEcc, bgMode)

        if (bitmaps.isEmpty()) {
            statusText = "ERROR: Could not generate QR codes for throughput (size=$frameSize)"
            setContent { VauchiTheme { BeaconScreen(null, statusText, currentSeq, bgMode == "inverted") } }
            return
        }

        log("Generated ${bitmaps.size}/$frameCount throughput frames")
        currentBitmap = bitmaps[0]
        currentSeq = 1
        val total = bitmaps.size
        statusText = "THROUGHPUT: 1/$total @ ${fps}fps | ${frameSize}B/frame"

        setContent {
            VauchiTheme {
                LaunchedEffect(Unit) {
                    var idx = 0
                    var lastTime = System.nanoTime()
                    var frameCounter = 0
                    var effectiveFps = fps.toFloat()
                    while (true) {
                        currentBitmap = bitmaps[idx]
                        currentSeq = idx + 1
                        frameCounter++
                        val now = System.nanoTime()
                        val elapsed = (now - lastTime) / 1_000_000_000.0
                        if (elapsed >= 1.0) {
                            effectiveFps = frameCounter / elapsed.toFloat()
                            frameCounter = 0
                            lastTime = now
                        }
                        statusText = "THROUGHPUT: ${idx + 1}/$total @ %.1ffps | ${frameSize}B/frame".format(effectiveFps)
                        idx = (idx + 1) % bitmaps.size
                        delay(frameDelayMs)
                    }
                }
                BeaconScreen(currentBitmap, statusText, currentSeq, bgMode == "inverted")
            }
        }
    }

    /** Resolve data size: preset overrides explicit data_size. */
    private fun resolveDataSize(
        preset: String?,
        fallback: Int,
    ): Int = if (preset != null) PAYLOAD_PRESETS[preset] ?: fallback else fallback

    /** Pre-generate QR bitmaps for a sequence of frames. */
    private fun generateBitmaps(
        count: Int,
        dataSize: Int,
        forceLowEcc: Boolean,
        bgMode: String,
    ): List<Bitmap> {
        val gray = bgMode == "gray"
        val inverted = bgMode == "inverted"
        val light = bgMode == "light"
        val bitmaps = mutableListOf<Bitmap>()

        for (seq in 1..count) {
            val payload = generatePayload(seq, dataSize)
            try {
                bitmaps.add(
                    generateBeaconQrBitmap(payload, lowErrorCorrection = forceLowEcc, gray = gray, inverted = inverted, light = light),
                )
                if (seq == 1) log("Payload sample (${payload.length} chars): ${payload.take(60)}...")
            } catch (e: Exception) {
                log("ERROR generating QR for seq=$seq size=$dataSize: ${e.message}")
                if (!forceLowEcc) {
                    try {
                        bitmaps.add(
                            generateBeaconQrBitmap(payload, lowErrorCorrection = true, gray = gray, inverted = inverted, light = light),
                        )
                        log("  -> Succeeded with low error correction")
                    } catch (e2: Exception) {
                        log("  -> FAILED even with low EC: ${e2.message}")
                    }
                } else {
                    log("  -> FAILED (already using low EC)")
                }
            }
        }

        log("Generated ${bitmaps.size}/$count QR codes")
        return bitmaps
    }

    private fun generatePayload(
        seq: Int,
        targetSize: Int,
    ): String {
        val prefix = "T:%03d:".format(seq)
        val remaining = targetSize - prefix.length
        if (remaining <= 0) return prefix.take(targetSize)
        val pad =
            buildString {
                var i = 0
                while (length < remaining) {
                    append("ABCDEFGHIJKLMNOP0123456789"[i % 26])
                    i++
                }
            }
        return prefix + pad
    }

    /**
     * Generate QR bitmap with color variants:
     * - default: black on white
     * - gray: dark gray (#404040) on light gray (#E0E0E0)
     * - inverted: white on black
     * - light: light gray (#808080) on white
     */
    private fun generateBeaconQrBitmap(
        data: String,
        lowErrorCorrection: Boolean = false,
        gray: Boolean = false,
        inverted: Boolean = false,
        light: Boolean = false,
    ): Bitmap {
        val ecLevel = if (lowErrorCorrection) MobileErrorCorrectionLevel.L else MobileErrorCorrectionLevel.M

        val (fgColor, bgColor) =
            when {
                inverted -> android.graphics.Color.WHITE to android.graphics.Color.BLACK
                gray -> android.graphics.Color.rgb(64, 64, 64) to android.graphics.Color.rgb(224, 224, 224)
                light -> android.graphics.Color.rgb(128, 128, 128) to android.graphics.Color.WHITE
                else -> android.graphics.Color.BLACK to android.graphics.Color.WHITE
            }

        return generateQrBitmap(
            data = data,
            size = QR_RENDER_SIZE,
            errorCorrection = ecLevel,
            foreground = fgColor,
            background = bgColor,
            margin = 2,
        ) ?: throw IllegalStateException("QR generation failed for data of length ${data.length}")
    }

    private fun logBrightness() {
        val brightness =
            try {
                android.provider.Settings.System
                    .getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                -1
            }
        log("Screen brightness=$brightness/255")
    }

    private fun log(msg: String) {
        Log.i(TAG, "$LOG_PREFIX $msg")
    }
}

@Composable
private fun BeaconScreen(
    bitmap: Bitmap?,
    status: String,
    seq: Int,
    inverted: Boolean = false,
) {
    val screenBg = if (inverted) Color.Black else Color.White
    val textColor = if (inverted) Color.White else Color.Black
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(screenBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(8.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
        if (bitmap != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(screenBg)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR Code #$seq",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            text = "SEQ: $seq",
            modifier = Modifier.padding(8.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge,
            color = textColor,
        )
    }
}
