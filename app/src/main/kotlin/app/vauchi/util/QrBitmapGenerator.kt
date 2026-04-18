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
 * For black-on-white QR codes (the common case), uses ALPHA_8 format
 * (1 byte/pixel) and copies the grayscale buffer directly — no ARGB
 * mapping or intermediate IntArray.
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
        val qr =
            rustGenerateQrBitmap(
                data,
                size.toUInt(),
                errorCorrection,
                0u,
                255u,
                margin.toUInt(),
            )

        if (foreground == Color.BLACK && background == Color.WHITE) {
            // Fast path: grayscale buffer → ALPHA_8 bitmap directly (1 byte/pixel).
            // Invert: Rust uses 0=dark/255=light, ALPHA_8 uses 255=opaque/0=transparent.
            val alpha = ByteArray(qr.pixels.size) { i -> (255 - qr.pixels[i].toInt()).toByte() }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alpha))
            bitmap
        } else {
            // Color path: map grayscale → ARGB for custom foreground/background.
            val argb =
                IntArray(qr.pixels.size) { i ->
                    if (qr.pixels[i].toInt() == 0) foreground else background
                }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            bitmap.setPixels(argb, 0, size, 0, 0, size, size)
            bitmap
        }
    } catch (_: Exception) {
        null
    }
