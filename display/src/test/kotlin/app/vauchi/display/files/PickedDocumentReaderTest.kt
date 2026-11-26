// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

// Feature: backup_recovery.feature @backup
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class PickedDocumentReaderTest {
    private val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
    private val native = shadowOf(resolver)
    private val reader = PickedDocumentReader(resolver)
    private val uri = Uri.parse("content://picked/document/opaque")

    private class ObservedStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false
        var delivered = 0

        override fun read(
            buffer: ByteArray,
            offset: Int,
            count: Int,
        ): Int = super.read(buffer, offset, count).also { if (it > 0) delivered += it }

        override fun read(): Int = super.read().also { if (it >= 0) delivered++ }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun exactLimitReturnsUnsignedBytesAndClosesTheNativeStream() {
        val stream = ObservedStream(byteArrayOf(0, 127, -128, -1))
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Selected(listOf(0u, 127u, 128u, 255u), "file"), reader.read(uri, 4uL))
        assertTrue(stream.closed)
        assertEquals(4, stream.delivered)
    }

    @Test
    fun aLyingSizeHintCannotAllowAnOversizedDocument() {
        val stream = ObservedStream(ByteArray(200_000))
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Failed(FilePickFailure.TooLarge), reader.read(uri, 3uL))
        assertEquals(4, stream.delivered)
        assertTrue(stream.closed)
    }

    @Test
    fun aZeroByteLimitAcceptsEmptyFilesAndRejectsEvenOneByte() {
        native.registerInputStream(uri, ByteArrayInputStream(byteArrayOf()))
        assertEquals(FilePickOutcome.Selected(emptyList(), "file"), reader.read(uri, 0uL))
        native.registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(FilePickOutcome.Failed(FilePickFailure.TooLarge), reader.read(uri, 0uL))
    }

    @Test
    fun unsignedLimitsCannotOverflowTheNativeReadBudget() {
        native.registerInputStream(uri, ByteArrayInputStream(byteArrayOf(-1)))
        assertEquals(FilePickOutcome.Selected(listOf(255u), "file"), reader.read(uri, ULong.MAX_VALUE))
    }

    @Test
    fun rawFilePathsAndWebUrisAreRefusedWithoutReadingThem() {
        for (raw in listOf("file:///private/identity", "https://example.invalid/data", "/private/identity")) {
            val other = Uri.parse(raw)
            val stream = ObservedStream(byteArrayOf(1))
            native.registerInputStream(other, stream)
            assertEquals(FilePickOutcome.Failed(FilePickFailure.UnsupportedFile), reader.read(other, 10uL))
            assertEquals(0, stream.delivered)
        }
    }

    @Test
    fun cancellationBeforeOpeningDoesNotAcquireAProviderStream() {
        val stream = ObservedStream(byteArrayOf(1))
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Cancelled, reader.read(uri, 10uL) { true })
        assertEquals(0, stream.delivered)
        assertEquals(false, stream.closed)
    }

    @Test
    fun cancellationDuringAReadDropsTheBytesAndClosesTheStream() {
        var canceled = false
        var closed = false
        val stream =
            object : InputStream() {
                override fun read(): Int = error("bulk reader expected")

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    bytes[offset] = 42
                    canceled = true
                    return 1
                }

                override fun close() {
                    closed = true
                }
            }
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Cancelled, reader.read(uri, 10uL) { canceled })
        assertTrue(closed)
    }

    @Test
    fun providerFailuresDoNotExposePathsOrPartialBytes() {
        var closed = false
        val stream =
            object : InputStream() {
                override fun read(): Int = throw IOException("private provider path")

                override fun close() {
                    closed = true
                }
            }
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Failed(FilePickFailure.ReadFailed), reader.read(uri, 10uL))
        assertTrue(closed)
    }

    @Test
    fun zeroLengthBulkReadsCannotStallCompletion() {
        val stream =
            object : ByteArrayInputStream(byteArrayOf(42)) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        native.registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Selected(listOf(42u), "file"), reader.read(uri, 10uL))
    }
}
