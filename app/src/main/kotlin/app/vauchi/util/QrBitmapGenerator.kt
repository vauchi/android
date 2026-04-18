// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.graphics.Bitmap
import android.graphics.Color
import uniffi.vauchi_platform.MobileQrEccLevel
import uniffi.vauchi_platform.generateQrBitmap as rustGenerateQrBitmap

/**
 * Generates a QR code bitmap using the Rust qrcode crate via UniFFI.
 * Pixel rendering happens entirely in Rust — this is a thin wrapper
 * that maps grayscale output to ARGB and wraps in an Android Bitmap.
 *
 * @param data The string to encode as a QR code.
 * @param size The width and height of the output bitmap in pixels.
 * @param errorCorrection Error correction level (default Medium).
 * @param foreground Foreground (dark module) color (default BLACK).
 * @param background Background (light module) color (default WHITE).
 * @param margin Number of quiet-zone modules around the QR (default 4).
 * @return A [Bitmap] containing the QR code, or null if encoding fails.
 */
fun generateQrBitmap(
    data: String,
    size: Int = 512,
    errorCorrection: MobileQrEccLevel = MobileQrEccLevel.MEDIUM,
    foreground: Int = Color.BLACK,
    background: Int = Color.WHITE,
    margin: Int = 4,
): Bitmap? =
    try {
        // Core renders grayscale: 0 = dark, 255 = light
        val qr =
            rustGenerateQrBitmap(
                data,
                size.toUInt(),
                errorCorrection,
                0u,
                255u,
                margin.toUInt(),
            )
        val grayscale = qr.pixels

        // Map grayscale → ARGB colors
        val argb =
            IntArray(grayscale.size) { i ->
                if (grayscale[i].toInt() == 0) foreground else background
            }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bitmap.setPixels(argb, 0, size, 0, 0, size, size)
        bitmap
    } catch (_: Exception) {
        null
    }
