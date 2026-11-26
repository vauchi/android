// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.graphics.Bitmap
import app.vauchi.contract.PresentationQrErrorCorrection
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeQrBitmapTest {
    private fun decode(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }

    @Test
    fun nativeBitmapsRetainPayloadsAndAnOpaqueWhiteQuietZone() {
        for (payload in listOf("opaque-qr", "é😀", "before\u0000after")) {
            for (level in listOf(null, PresentationQrErrorCorrection.Low, PresentationQrErrorCorrection.Medium)) {
                val bitmap = NativeQrBitmap.encode(payload, level)
                assertEquals(payload, decode(bitmap))
                assertEquals(android.graphics.Color.WHITE, bitmap!!.getPixel(0, 0))
                assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
            }
        }
    }

    @Test
    fun malformedAndImpossiblePayloadsHaveNoBitmap() {
        for (payload in listOf("", "\uD800", "x".repeat(7090))) assertNull(NativeQrBitmap.encode(payload))
    }
}
