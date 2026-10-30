// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Camera frames, as the decoder sees them, become the scanner's real-frame
 * test corpus (vauchi/private#931, #933).
 */
class FrameDumpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(fill: Int) = ByteArray(4 * 3) { fill.toByte() }

    private fun names(dir: File) = dir.list()!!.sorted()

    @Test
    fun every_nth_frame_is_written_with_its_index_size_and_rotation() {
        val dir = tmp.newFolder()
        val dump = FrameDump(dir, everyNth = 2, maxFrames = 10)

        (0 until 5).forEach { dump.offer(frame(it), width = 4, height = 3, rotationDegrees = 270) }

        assertEquals(listOf("f0_4x3_r270.y", "f2_4x3_r270.y", "f4_4x3_r270.y"), names(dir))
        assertArrayEquals(frame(2), File(dir, "f2_4x3_r270.y").readBytes())
    }

    @Test
    fun writing_stops_at_the_frame_limit() {
        val dir = tmp.newFolder()
        val dump = FrameDump(dir, everyNth = 1, maxFrames = 2)

        (0 until 5).forEach { dump.offer(frame(it), width = 4, height = 3, rotationDegrees = 0) }

        assertEquals(listOf("f0_4x3_r0.y", "f1_4x3_r0.y"), names(dir))
    }

    @Test
    fun a_debug_build_with_the_dump_folder_writes_into_a_new_run_folder() {
        val filesDir = tmp.newFolder()
        File(filesDir, "qr-dump").mkdir()

        val dump = FrameDump.enabledIn(filesDir, debuggable = true, runName = "run-1")!!
        dump.offer(frame(7), width = 4, height = 3, rotationDegrees = 90)

        assertEquals(listOf("f0_4x3_r90.y"), names(File(filesDir, "qr-dump/run-1")))
    }

    @Test
    fun without_the_dump_folder_nothing_is_dumped() {
        assertNull(FrameDump.enabledIn(tmp.newFolder(), debuggable = true, runName = "run-1"))
    }

    @Test
    fun a_release_build_never_dumps_even_with_the_folder() {
        val filesDir = tmp.newFolder()
        File(filesDir, "qr-dump").mkdir()

        assertNull(FrameDump.enabledIn(filesDir, debuggable = false, runName = "run-1"))
        assertEquals(emptyList<String>(), names(File(filesDir, "qr-dump")))
    }
}
