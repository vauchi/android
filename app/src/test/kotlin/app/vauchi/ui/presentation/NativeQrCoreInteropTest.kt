// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import android.graphics.Color
import app.vauchi.contract.PresentationQrErrorCorrection
import app.vauchi.display.NativeQrBitmap
import app.vauchi.screenshots.HostCoreLibrary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.MobileScannerBackend
import uniffi.vauchi_platform.scanQr

// Feature: generic_presentation_protocol.feature
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class NativeQrCoreInteropTest {
    @Before
    fun requireMatchingHostCore() = HostCoreLibrary.assumePresentInSharedSandbox()

    @Test
    fun displayBitmapsPreserveOpaquePayloadsThroughTheExistingCoreScanner() {
        val payloads = listOf("opaque-qr", "é😀", "before\u0000after", "vauchi://opaque?value=a%2Bb%2Fc")
        for (payload in payloads) {
            for (level in listOf(PresentationQrErrorCorrection.Low, PresentationQrErrorCorrection.Medium)) {
                val bitmap = checkNotNull(NativeQrBitmap.encode(payload, level))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val luma = ByteArray(pixels.size) { Color.red(pixels[it]).toByte() }
                val result = scanQr(MobileScannerBackend.RQRR_PREPROCESSED, luma, bitmap.width.toUInt(), bitmap.height.toUInt())
                assertEquals(payload, result.decoded)
                assertEquals(listOf(payload), result.decodedAll)
            }
        }
    }
}
