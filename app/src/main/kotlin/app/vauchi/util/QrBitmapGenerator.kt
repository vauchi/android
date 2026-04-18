// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.graphics.Bitmap
import android.graphics.Color
import uniffi.vauchi_platform.MobileErrorCorrectionLevel
import uniffi.vauchi_platform.generateQrModules

/**
 * Generates a QR code bitmap using the Rust qrcode crate via UniFFI.
 * Replaces com.google.zxing.qrcode.QRCodeWriter.
 *
 * @param data The string to encode as a QR code.
 * @param size The width and height of the output bitmap in pixels.
 * @param errorCorrection Error correction level (default M).
 * @param foreground Foreground (dark module) color (default BLACK).
 * @param background Background (light module) color (default WHITE).
 * @param margin Number of quiet-zone modules around the QR (default 3).
 * @return A [Bitmap] containing the QR code, or null if encoding fails.
 */
fun generateQrBitmap(
    data: String,
    size: Int = 512,
    errorCorrection: MobileErrorCorrectionLevel = MobileErrorCorrectionLevel.M,
    foreground: Int = Color.BLACK,
    background: Int = Color.WHITE,
    margin: Int = 3,
): Bitmap? =
    try {
        val qr = generateQrModules(data, errorCorrection)
        val moduleCount = qr.width.toInt()
        val totalModules = moduleCount + 2 * margin
        val scale = size.toFloat() / totalModules

        val pixels = IntArray(size * size) { background }

        for (row in 0 until moduleCount) {
            for (col in 0 until moduleCount) {
                if (qr.modules[row * moduleCount + col]) {
                    val x0 = ((col + margin) * scale).toInt()
                    val y0 = ((row + margin) * scale).toInt()
                    val x1 = (((col + margin + 1) * scale).toInt()).coerceAtMost(size)
                    val y1 = (((row + margin + 1) * scale).toInt()).coerceAtMost(size)
                    for (py in y0 until y1) {
                        for (px in x0 until x1) {
                            pixels[py * size + px] = foreground
                        }
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap
    } catch (_: Exception) {
        null
    }
