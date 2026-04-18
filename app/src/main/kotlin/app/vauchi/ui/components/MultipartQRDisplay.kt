// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.util.generateQrBitmap
import kotlinx.coroutines.delay

/**
 * Animated Compose component that cycles through multipart QR code images.
 *
 * Used for offline device linking when the payload exceeds a single QR code's capacity.
 * Each chunk is displayed as a QR code at ~3fps, with progress indication.
 *
 * @param chunks List of QR chunk strings to display as QR codes.
 * @param modifier Modifier for the root layout.
 * @param qrSize Size in pixels for the generated QR bitmaps (default 512).
 * @param cycleDelayMs Delay between frames in milliseconds (default 333ms for ~3fps).
 */
@Composable
fun MultipartQRDisplay(
    chunks: List<String>,
    modifier: Modifier = Modifier,
    qrSize: Int = 512,
    cycleDelayMs: Long = 333L,
) {
    require(chunks.isNotEmpty()) { "chunks must not be empty" }

    var currentIndex by remember { mutableIntStateOf(0) }

    // Pre-generate all QR bitmaps so cycling is smooth
    val bitmaps =
        remember(chunks, qrSize) {
            chunks.map { chunk -> generateQRBitmap(chunk, qrSize) }
        }

    // Animate at ~3fps
    LaunchedEffect(chunks, cycleDelayMs) {
        if (chunks.size > 1) {
            while (true) {
                delay(cycleDelayMs)
                currentIndex = (currentIndex + 1) % chunks.size
            }
        }
    }

    // Reset index when chunks change
    LaunchedEffect(chunks) {
        currentIndex = 0
    }

    Column(
        modifier =
            modifier
                .semantics {
                    contentDescription = "Multipart QR code display, showing part ${currentIndex + 1} of ${chunks.size}"
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val bitmap = bitmaps.getOrNull(currentIndex)

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code part ${currentIndex + 1} of ${chunks.size}",
                modifier = Modifier.size(250.dp),
            )
        } else {
            // Fallback if bitmap generation failed
            Box(
                modifier = Modifier.size(250.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "QR generation failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = "Part ${currentIndex + 1} of ${chunks.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { (currentIndex + 1).toFloat() / chunks.size },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )
    }
}

/**
 * Generates a QR code bitmap from the given content string.
 *
 * @param content The string to encode as a QR code.
 * @param size The width and height of the output bitmap in pixels.
 * @return A [Bitmap] containing the QR code, or null if encoding fails.
 */
private fun generateQRBitmap(
    content: String,
    size: Int = 512,
): Bitmap? = generateQrBitmap(data = content, size = size)
