// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.provider.OpenableColumns
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — prepared export metadata and bytes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportMetadataTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun generatedPreparedMetadataAndBytesRemainExactThroughTheNativeProvider() {
        val owner = rig.owner(rig.host(FileExportLimits(maxFileBytes = 4, maxCacheBytes = 4096, maxFiles = 128)))
        runBlocking {
            checkAll(
                100,
                Arb.list(Arb.element("a", "Z", "0", "é", "秘", "🔐"), 0..12),
                Arb.list(Arb.byte(), 0..4),
                Arb.element("application/json", "text/plain", "application/vnd.test+bin", "image/svg+xml"),
            ) { pieces, bytes, mime ->
                val name = "秘密-${pieces.joinToString("")}-🔐.bin"
                val file = rig.spec.copy(data = bytes.map { it.toUByte() }, mimeType = mime, suggestedName = "../../$name")
                assertTrue(owner.executeAsync(Command.ExportFile(file)))
                val uri = rig.takeUri()
                assertArrayEquals(bytes.toByteArray(), rig.read(uri))
                assertEquals(mime, rig.activity.contentResolver.getType(uri))
                rig.activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(name, it.getString(0))
                }
            }
        }
        assertEquals(emptyList<Event>(), rig.events)
        assertEquals(100, rig.directory.listFiles()!!.size)
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun traversalAndControlShapedNamesBecomeSafeNativeDisplayMetadata() {
        val owner = rig.owner()
        val names =
            mapOf(
                "" to "vauchi-export",
                "." to "vauchi-export",
                ".." to "vauchi-export",
                "/" to "vauchi-export",
                "..\\..\\秘密.vcf" to "秘密.vcf",
                "nul\u0000line\n.txt" to "nulline.txt",
            )
        runBlocking {
            for ((input, expected) in names) {
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec.copy(suggestedName = input))))
                val uri = rig.takeUri()
                rig.activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(expected, it.getString(0))
                }
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(uri))
            }
        }
        assertEquals(names.size, rig.directory.listFiles()!!.size)
        assertEquals(emptyList<Event>(), rig.events)
    }

    @Test
    fun invalidNativeLimitConfigurationsFailWithoutAcquiringFiles() {
        for (limits in listOf<() -> FileExportLimits>(
            { FileExportLimits(maxFileBytes = -1) },
            { FileExportLimits(maxCacheBytes = 0) },
            { FileExportLimits(maxFileBytes = 4, maxCacheBytes = 3) },
            { FileExportLimits(maxFiles = 0) },
            { FileExportLimits(maxFiles = 1025) },
            { FileExportLimits(retentionMillis = 0) },
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) { limits() }
            assertNull(error.cause)
        }
        assertEquals(false, rig.directory.exists())
        assertEquals(emptyList<Event>(), rig.events)
    }
}
