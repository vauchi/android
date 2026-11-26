// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.InputStream

// Feature: backup_recovery.feature @backup
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class PickedDocumentMetadataTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val resolver = application.contentResolver
    private val reader = PickedDocumentReader(resolver)
    private val uri = Uri.parse("content://picked/item")
    private val provider =
        SelectedDocumentProvider().also {
            it.attachInfo(application, ProviderInfo().apply { authority = "picked" })
            ShadowContentResolver.registerProviderInternal("picked", it)
        }

    @Test
    fun metadataIsOpaqueAndSizeHintsCannotReplaceCountingActualBytes() {
        provider.name = "../contacts-é.vcf"
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2)))
        assertEquals(FilePickOutcome.Selected(listOf(1u, 2u), provider.name!!), reader.read(uri, 2uL))
        assertTrue(provider.lastCursor!!.isClosed)
    }

    @Test
    fun oversizedUtf8NamesAndEmbeddedNulAreRejectedBeforeOpening() {
        for (name in listOf("x".repeat(1025), "é".repeat(513), "invalid\u0000name")) {
            provider.name = name
            val stream = ByteArrayInputStream(byteArrayOf(1))
            shadowOf(resolver).registerInputStream(uri, stream)
            assertEquals(FilePickOutcome.Failed(FilePickFailure.UnsupportedFile), reader.read(uri, 2uL))
            assertEquals(1, stream.available())
            assertTrue(provider.lastCursor!!.isClosed)
        }
    }

    @Test
    fun absentNamesUseNeutralMetadataWithoutExposingTheUriPath() {
        provider.name = null
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(FilePickOutcome.Selected(listOf(1u), "file"), reader.read(uri, 2uL))
        assertTrue(provider.lastCursor!!.isClosed)
    }

    @Test
    fun theCanonicalEventCeilingAppliesEvenToAnUnsignedUnboundedRequest() {
        var consumed = 0
        var closed = false
        val stream =
            object : InputStream() {
                override fun read(): Int {
                    consumed++
                    return 0
                }

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    consumed += length
                    return length
                }

                override fun close() {
                    closed = true
                }
            }
        shadowOf(resolver).registerInputStream(uri, stream)
        assertEquals(FilePickOutcome.Failed(FilePickFailure.TooLarge), reader.read(uri, ULong.MAX_VALUE))
        assertEquals(32 * 1024 * 1024 + 1, consumed)
        assertTrue(closed)
        assertTrue(provider.lastCursor!!.isClosed)
    }
}

class SelectedDocumentProvider : ContentProvider() {
    var name: String? = "file"
    var beforeQuery: (() -> Unit)? = null
    var lastCursor: MatrixCursor? = null

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): MatrixCursor {
        beforeQuery?.invoke()
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).also {
            it.addRow(arrayOf<Any?>(name, Long.MAX_VALUE))
            lastCursor = it
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
