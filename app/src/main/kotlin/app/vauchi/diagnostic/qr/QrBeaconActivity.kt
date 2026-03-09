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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import app.vauchi.ui.theme.VauchiTheme
import kotlinx.coroutines.delay

/**
 * QR Beacon: displays QR codes on screen for another device to scan.
 *
 * Modes:
 *   static  — one QR, fixed data size, shown indefinitely
 *   cycle   — sequential QR codes, cycling at configurable interval
 *
 * Launch via ADB:
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --ei data_size 200 --ei cycle_ms 0 --ei count 1
 *   adb shell am start -n app.vauchi/.diagnostic.qr.QrBeaconActivity \
 *     --ei data_size 100 --ei cycle_ms 500 --ei count 20
 *
 * QR payload format: T:<seq_num>:<padded_data>
 * - seq_num: 3-digit zero-padded sequence (001, 002, ...)
 * - padded_data: deterministic payload to reach target data_size
 */
class QrBeaconActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Vauchi"
        private const val LOG_PREFIX = "[QR Beacon]"
        private const val QR_RENDER_SIZE = 800
    }

    private var currentBitmap by mutableStateOf<Bitmap?>(null)
    private var statusText by mutableStateOf("")
    private var currentSeq by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataSize = intent?.getIntExtra("data_size", 200) ?: 200
        val cycleMs = intent?.getIntExtra("cycle_ms", 0) ?: 0
        val count = intent?.getIntExtra("count", 1) ?: 1
        val bgMode = intent?.getStringExtra("bg") ?: "bw" // bw, gray, inverted, light

        // Log brightness
        val brightness =
            try {
                android.provider.Settings.System
                    .getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                -1
            }
        log("Starting beacon: data_size=$dataSize cycle_ms=$cycleMs count=$count bg=$bgMode brightness=$brightness/255")

        // Pre-generate all QR bitmaps
        val bitmaps = mutableListOf<Bitmap>()
        for (seq in 1..count) {
            val payload = generatePayload(seq, dataSize)
            try {
                bitmaps.add(generateQrBitmap(payload, gray = bgMode == "gray", inverted = bgMode == "inverted", light = bgMode == "light"))
                if (seq == 1) log("Payload sample (${payload.length} chars): ${payload.take(60)}...")
            } catch (e: Exception) {
                log("ERROR generating QR for seq=$seq size=$dataSize: ${e.message}")
                // Try with smaller error correction
                try {
                    bitmaps.add(
                        generateQrBitmap(
                            payload,
                            lowErrorCorrection = true,
                            gray = bgMode == "gray",
                            inverted =
                                bgMode == "inverted",
                            light = bgMode == "light",
                        ),
                    )
                    log("  -> Succeeded with low error correction")
                } catch (e2: Exception) {
                    log("  -> FAILED even with low EC: ${e2.message}")
                }
            }
        }

        log("Generated ${bitmaps.size}/$count QR codes")
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

    private fun generatePayload(
        seq: Int,
        targetSize: Int,
    ): String {
        val prefix = "T:%03d:".format(seq)
        val remaining = targetSize - prefix.length
        if (remaining <= 0) return prefix.take(targetSize)
        // Deterministic padding using hex-like chars for QR alphanumeric efficiency
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
     * - light: light gray (#808080) on white (more white, less black)
     */
    private fun generateQrBitmap(
        data: String,
        lowErrorCorrection: Boolean = false,
        gray: Boolean = false,
        inverted: Boolean = false,
        light: Boolean = false,
    ): Bitmap {
        val writer = QRCodeWriter()
        val hints = mutableMapOf<EncodeHintType, Any>()
        if (lowErrorCorrection) {
            hints[EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L
        }
        hints[EncodeHintType.MARGIN] = 2
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, QR_RENDER_SIZE, QR_RENDER_SIZE, hints)
        val bitmap = Bitmap.createBitmap(QR_RENDER_SIZE, QR_RENDER_SIZE, Bitmap.Config.RGB_565)

        val (fgColor, bgColor) =
            when {
                inverted -> android.graphics.Color.WHITE to android.graphics.Color.BLACK
                gray -> android.graphics.Color.rgb(64, 64, 64) to android.graphics.Color.rgb(224, 224, 224)
                light -> android.graphics.Color.rgb(128, 128, 128) to android.graphics.Color.WHITE
                else -> android.graphics.Color.BLACK to android.graphics.Color.WHITE
            }

        for (x in 0 until QR_RENDER_SIZE) {
            for (y in 0 until QR_RENDER_SIZE) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) fgColor else bgColor)
            }
        }
        return bitmap
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
