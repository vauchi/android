// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import app.vauchi.contract.Command
import app.vauchi.contract.Event
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Files

// Feature: generic_presentation_protocol.feature — bounded native export cache ownership.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportCacheLimitsTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun repeatedPreparedNamesHaveIndependentFilesAndExactBytes() {
        val owner = rig.owner()
        runBlocking {
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            val first = rig.takeUri()
            val next = rig.spec.copy(data = listOf(42u.toUByte()))
            assertTrue(owner.executeAsync(Command.ExportFile(next)))
            val second = rig.takeUri()
            assertNotEquals(first, second)
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(first))
            assertArrayEquals(byteArrayOf(42), rig.read(second))
            assertEquals(2, rig.directory.listFiles()!!.size)
            assertEquals(emptyList<Event>(), rig.events)
        }
    }

    @Test
    fun youngFileQuotaRefusesNewWorkAndExpiryAllowsLaterExport() {
        var now = System.currentTimeMillis()
        val owner = rig.owner(rig.host(FileExportLimits(maxFileBytes = 4, maxCacheBytes = 8, maxFiles = 1, retentionMillis = 10), { now }))
        runBlocking {
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            val first = rig.takeUri()
            assertTrue(
                rig.directory
                    .listFiles()!!
                    .single()
                    .setLastModified(now),
            )
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertEquals(listOf(Event.HardwareError("file_export_cache_full", "file_export")), rig.events)
            assertNull(shadowOf(rig.activity).nextStartedActivity)
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(first))
            now += 11
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            val replacement = rig.takeUri()
            assertNotEquals(first, replacement)
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(replacement))
            assertThrows(FileNotFoundException::class.java) { rig.read(first) }
            assertEquals(1, rig.directory.listFiles()!!.size)
            assertEquals(1, rig.events.size)
        }
    }

    @Test
    fun byteQuotaNeverDeletesYoungSharedFiles() {
        val owner = rig.owner(rig.host(FileExportLimits(maxFileBytes = 4, maxCacheBytes = 5)))
        runBlocking {
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            val first = rig.takeUri()
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertEquals(listOf(Event.HardwareError("file_export_cache_full", "file_export")), rig.events)
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(first))
            assertEquals(1, rig.directory.listFiles()!!.size)
            assertNull(shadowOf(rig.activity).nextStartedActivity)
        }
    }

    @Test
    fun oversizedPayloadAndMalformedMetadataFailBeforeNativeWrite() {
        var opened = false
        val owner =
            rig.owner(
                rig.host(openOutput = {
                    opened = true
                    throw AssertionError("Must not write invalid export")
                }),
            )
        val oversized =
            object : AbstractList<UByte>() {
                override val size: Int get() = 128 * 1024 * 1024 + 1

                override fun get(index: Int): UByte = throw AssertionError("Must not read oversized export")
            }
        runBlocking {
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec.copy(data = oversized))))
            for (mime in listOf("", "text/plain\r\nprivate", "text/plain/extra", "x".repeat(256))) {
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec.copy(mimeType = mime))))
            }
            for (name in listOf("x".repeat(1025), "🔐".repeat(300))) {
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec.copy(suggestedName = name))))
            }
        }
        assertEquals(false, opened)
        assertEquals(
            listOf(Event.HardwareError("file_export_too_large", "file_export")) +
                List(6) { Event.HardwareError("file_export_invalid_request", "file_export") },
            rig.events,
        )
        assertEquals(false, rig.directory.exists())
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun escapedRootIsRefusedWithoutChangingOutsideFiles() {
        val outside = File(rig.activity.cacheDir, "outside").apply { mkdirs() }
        val retained = File(outside, "retained").apply { writeText("untouched") }
        Files.createSymbolicLink(rig.directory.toPath(), outside.toPath())
        runBlocking { assertTrue(rig.owner().executeAsync(Command.ExportFile(rig.spec))) }
        assertEquals(listOf(Event.HardwareError("file_export_cache_unavailable", "file_export")), rig.events)
        assertEquals("untouched", retained.readText())
        assertEquals(listOf("retained"), outside.list()!!.toList())
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun unexpectedCacheEntriesArePreservedAndRefuseNewWrites() {
        rig.directory.mkdirs()
        val retained =
            File(rig.directory, "foreign-state").apply {
                writeText("preserved")
                setLastModified(1)
            }
        runBlocking { assertTrue(rig.owner().executeAsync(Command.ExportFile(rig.spec))) }
        assertEquals(listOf(Event.HardwareError("file_export_cache_unavailable", "file_export")), rig.events)
        assertEquals("preserved", retained.readText())
        assertEquals(listOf("foreign-state"), rig.directory.list()!!.toList())
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun existingNativeFileLockRefusesWithoutQueuingAndLaterRecovers() {
        val owner = rig.owner()
        RandomAccessFile(File(rig.activity.cacheDir, "vauchi-display-export.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                runBlocking { assertTrue(owner.executeAsync(Command.ExportFile(rig.spec))) }
                assertEquals(listOf(Event.HardwareError("file_export_busy", "file_export")), rig.events)
                assertNull(shadowOf(rig.activity).nextStartedActivity)
            }
        }
        runBlocking { assertTrue(owner.executeAsync(Command.ExportFile(rig.spec))) }
        assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
        assertEquals(1, rig.events.size)
    }
}
