// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.util.LocalizationManager
import app.vauchi.util.generateQrBitmap
import kotlinx.coroutines.delay

/**
 * QR code display with a live countdown timer.
 *
 * Shows the device link QR code and counts down to expiry. Calls [onExpired]
 * when time runs out so the parent can transition to an expired state.
 *
 * @param qrData The QR code data string.
 * @param expiresAt Unix timestamp (seconds) when the QR expires.
 * @param localizationManager For localized strings.
 * @param onCopy Callback when the user taps "Copy Link".
 * @param onExpired Callback when the countdown reaches zero.
 */
@Composable
fun QRCountdownContent(
    qrData: String,
    expiresAt: ULong,
    localizationManager: LocalizationManager,
    onCopy: () -> Unit,
    onExpired: () -> Unit,
) {
    var remainingSeconds by remember { mutableIntStateOf(0) }

    // Countdown tick
    LaunchedEffect(expiresAt) {
        while (true) {
            val now = (System.currentTimeMillis() / 1000).toULong()
            if (now >= expiresAt) {
                remainingSeconds = 0
                onExpired()
                break
            }
            remainingSeconds = (expiresAt - now).toInt()
            delay(1000)
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format("%d:%02d", minutes, seconds)
    val isUrgent = remainingSeconds <= 60

    Text(
        text = "Scan this QR code on your new device",
        style = MaterialTheme.typography.bodyMedium,
    )

    // QR Code
    val qrBitmap =
        remember(qrData) {
            generateQRBitmap(qrData, 250)
        }
    if (qrBitmap != null) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = Color.White,
                ),
        ) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Device Link QR Code",
                modifier =
                    Modifier
                        .size(250.dp)
                        .padding(8.dp),
            )
        }
    }

    // Countdown timer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.semantics {
                contentDescription = "QR code expires in $remainingSeconds seconds"
            },
    ) {
        Icon(
            Icons.Default.Share, // Using available icon; clock not in default set
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint =
                if (isUrgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            text = "Expires in $formattedTime",
            style = MaterialTheme.typography.labelMedium,
            color =
                if (isUrgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }

    // Waiting indicator
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = "Waiting for new device to connect...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Copy button
    TextButton(onClick = onCopy) {
        Icon(
            Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Copy Link")
    }

    Text(
        text =
            "Open Vauchi on your new device and select " +
                "\"Join Existing Identity\" to scan this code.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Generate a QR code bitmap from a string.
 */
private fun generateQRBitmap(
    data: String,
    size: Int,
): Bitmap? =
    try {
        generateQrBitmap(data, size)
    } catch (_: Exception) {
        null
    }
