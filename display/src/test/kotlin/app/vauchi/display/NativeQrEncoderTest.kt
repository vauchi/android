// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.PresentationQrErrorCorrection
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQrEncoderTest {
    private fun decode(symbol: QrSymbol): Result {
        val pixels =
            IntArray(symbol.side * symbol.side) { index ->
                if (symbol[index % symbol.side, index / symbol.side]) -0x1000000 else -1
            }
        val source = RGBLuminanceSource(symbol.side, symbol.side, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
    }

    @Test
    fun aSmallSymbolHasItsFourModuleWhiteQuietZone() {
        val symbol = NativeQrEncoder.encode("hello") ?: error("no symbol")

        assertEquals(29, symbol.side)
        for (y in 0 until symbol.side) {
            for (x in 0 until symbol.side) {
                if (x < 4 || y < 4 || x >= symbol.side - 4 || y >= symbol.side - 4) {
                    assertEquals(false, symbol[x, y])
                }
            }
        }
        assertEquals(true, symbol[4, 4])
        assertEquals("hello", decode(symbol).text)
    }

    @Test
    fun suppliedAndDefaultCorrectionLevelsReachTheEncodedSymbol() {
        for ((level, expected) in listOf(
            null to "M",
            PresentationQrErrorCorrection.Medium to "M",
            PresentationQrErrorCorrection.Low to "L",
        )) {
            val symbol = NativeQrEncoder.encode("a stable payload", level) ?: error("no symbol")
            val result = decode(symbol)

            assertEquals("a stable payload", result.text)
            assertEquals(expected, result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
        }
    }

    @Test
    fun emptyOversizedAndMalformedUnicodeInputAreRefused() {
        listOf("", "x".repeat(1_000_000), "\uD800", "\uDC00", "x".repeat(7089)).forEach { input ->
            assertNull(NativeQrEncoder.encode(input))
        }
    }

    @Test
    fun diagnosticsDoNotExposeEncodedPayloads() {
        val symbol = NativeQrEncoder.encode("private-test-payload") ?: error("no symbol")

        assertEquals("QrSymbol", symbol.toString())
    }

    @Test
    fun utf8PayloadsRoundTripThroughAStandardReader() =
        runBlocking<Unit> {
            checkAll(200, Arb.string(1..256), Arb.element("", "é", "😀", "\u0000", "\"\\\n")) { generated, edge ->
                val text = edge + generated + edge
                val symbol = NativeQrEncoder.encode(text) ?: error("no symbol")

                assertEquals(text, decode(symbol).text)
            }
        }
}
