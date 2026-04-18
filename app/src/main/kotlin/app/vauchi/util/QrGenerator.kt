// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.graphics.Bitmap
import android.graphics.Color
import uniffi.vauchi_platform.MobileQrEccLevel
import uniffi.vauchi_platform.generateQrMatrix

/**
 * Generate a QR code bitmap using core's qrcode crate via UniFFI.
 *
 * Replaces com.google.zxing:core for QR generation — no Google dependency.
 *
 * @param data The data to encode.
 * @param size Target bitmap size in pixels.
 * @param ecc Error correction level string ("L", "M", "Q", "H").
 * @param margin Quiet zone in modules (included in the matrix from core, so this
 *               parameter is ignored — core adds a 4-module quiet zone).
 * @param foreground Foreground (dark module) color, default black.
 * @param background Background (light module) color, default white.
 */
fun generateQrBitmap(
    data: String,
    size: Int = 512,
    ecc: String = "M",
    foreground: Int = Color.BLACK,
    background: Int = Color.WHITE,
): Bitmap {
    val eccLevel =
        when (ecc.uppercase()) {
            "H" -> MobileQrEccLevel.HIGH
            "Q" -> MobileQrEccLevel.QUARTILE
            "M" -> MobileQrEccLevel.MEDIUM
            else -> MobileQrEccLevel.LOW
        }

    val matrix = generateQrMatrix(data, eccLevel)
    val moduleSize = size / matrix.width.toInt()
    val actualSize = moduleSize * matrix.width.toInt()

    val bitmap = Bitmap.createBitmap(actualSize, actualSize, Bitmap.Config.RGB_565)
    val pixels = IntArray(actualSize * actualSize)

    for (my in 0 until matrix.width.toInt()) {
        for (mx in 0 until matrix.width.toInt()) {
            val dark = matrix.modules[(my * matrix.width.toInt() + mx)]
            val color = if (dark) foreground else background
            val px = mx * moduleSize
            val py = my * moduleSize
            for (dy in 0 until moduleSize) {
                for (dx in 0 until moduleSize) {
                    pixels[(py + dy) * actualSize + (px + dx)] = color
                }
            }
        }
    }
    bitmap.setPixels(pixels, 0, actualSize, 0, 0, actualSize, actualSize)
    return bitmap
}
