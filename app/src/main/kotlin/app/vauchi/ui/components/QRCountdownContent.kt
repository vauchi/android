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

    // TODO(HUMBLE): W, P2. Hardcoded English device-link copy. Fix: core
    // supplies localized label keys. (see _private problem record
    // 2026-07-06-mobile-domain-shell-violations)
    Text(
        text = "Scan this QR code on your new device",
        style = MaterialTheme.typography.bodyMedium,
    )

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
                contentDescription = localizationManager.t("device_link.a11y_qr"),
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
                contentDescription = localizationManager.t("qr.a11y_expires_in").replace("{seconds}", remainingSeconds.toString())
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
        // TODO(HUMBLE): W, P2. Hardcoded English countdown label. Fix: core
        // supplies localized label. (see _private problem record
        // 2026-07-06-mobile-domain-shell-violations)
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
        // TODO(HUMBLE): W, P2. Hardcoded English waiting label. Fix: core
        // supplies localized label. (see _private problem record
        // 2026-07-06-mobile-domain-shell-violations)
        Text(
            text = "Waiting for new device to connect...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = onCopy) {
        Icon(
            Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(localizationManager.t("device_link.copy_link"))
    }

    // TODO(HUMBLE): W, P2. Hardcoded English instructional copy. Fix: core
    // supplies localized label. (see _private problem record
    // 2026-07-06-mobile-domain-shell-violations)
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
): Bitmap? = app.vauchi.util.generateQrBitmap(data = data, size = size)
