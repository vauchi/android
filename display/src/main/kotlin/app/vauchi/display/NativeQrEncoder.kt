// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.PresentationQrErrorCorrection
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class QrSymbol internal constructor(
    val side: Int,
    private val modules: BooleanArray,
) {
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = modules[y * side + x]

    override fun toString(): String = "QrSymbol"
}

object NativeQrEncoder {
    // Version 40 numeric mode is the largest possible QR payload. Reject
    // impossible inputs before allocating encoder buffers or UTF8 bytes.
    private const val MAX_INPUT = 7089

    fun encode(
        data: String,
        level: PresentationQrErrorCorrection? = null,
    ): QrSymbol? {
        if (data.isEmpty() || data.length > MAX_INPUT) return null
        if (!Charsets.UTF_8.newEncoder().canEncode(data)) return null
        if (data.toByteArray(Charsets.UTF_8).size > MAX_INPUT) return null
        val correction =
            when (level) {
                PresentationQrErrorCorrection.Low -> ErrorCorrectionLevel.L
                PresentationQrErrorCorrection.Medium, null -> ErrorCorrectionLevel.M
            }
        return try {
            val matrix =
                QRCodeWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                        EncodeHintType.ERROR_CORRECTION to correction,
                        EncodeHintType.MARGIN to 4,
                    ),
                )
            val side = matrix.width
            QrSymbol(side, BooleanArray(side * side) { index -> matrix[index % side, index / side] })
        } catch (_: WriterException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
