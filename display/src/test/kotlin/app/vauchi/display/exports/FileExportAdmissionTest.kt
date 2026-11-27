// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.AndroidDisplayExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Feature: generic_presentation_protocol.feature — native export admission and recovery.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportAdmissionTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun refusedOrThrowingFailureAdmissionClosesBothOwnerAndNativeHost() {
        for (throws in listOf(false, true)) {
            val host = rig.host(openOutput = { throw IOException("private native path") })
            val observed = mutableListOf<Event>()
            val owner =
                AndroidDisplayExecutor(rig.activity, fileExportHost = host) {
                    observed.add(it)
                    if (throws) throw IllegalArgumentException("private admission diagnostic")
                    false
                }
            try {
                runBlocking {
                    assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                    assertFalse(owner.executeAsync(Command.ExportFile(rig.spec)))
                    assertEquals(Event.HardwareUnavailable("file_export"), host.export(rig.spec))
                }
                assertEquals(listOf(Event.HardwareError("file_export_failed", "file_export")), observed)
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertNull(shadowOf(rig.activity).nextStartedActivity)
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun alreadyCanceledCallerCannotAcquireActivityOrFilesAndOwnerRemainsUsable() {
        var acquired = 0
        val owner =
            rig.owner(
                rig.host(activity = {
                    acquired++
                    rig.activity
                }),
            )
        runBlocking {
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    owner.executeAsync(Command.ExportFile(rig.spec))
                }
            try {
                operation.await()
                fail("Canceled caller must not execute")
            } catch (_: CancellationException) {
            }
            assertTrue(operation.isCancelled)
            assertEquals(0, acquired)
            assertEquals(false, rig.directory.exists())
            assertNull(shadowOf(rig.activity).nextStartedActivity)
            assertEquals(emptyList<Event>(), rig.events)
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
        }
    }

    @Test
    fun failedCleanupPreservesTheNativeObstructionAndAllowsRetryAfterRepair() {
        var fail = true
        var obstruction: File? = null
        val owner =
            rig.owner(
                rig.host(openOutput = { output ->
                    if (fail) {
                        check(output.delete())
                        check(output.mkdir())
                        File(output, "obstruction").writeText("preserved")
                        obstruction = output
                        throw IOException("private native diagnostic")
                    }
                    FileOutputStream(output)
                }),
            )
        runBlocking {
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertEquals(true, currentCoroutineContext()[Job]!!.isActive)
            assertEquals(listOf(Event.HardwareError("file_export_cleanup_failed", "file_export")), rig.events)
            val retained = File(obstruction!!, "obstruction")
            assertEquals("preserved", retained.readText())
            assertEquals(1, rig.directory.listFiles()!!.size)
            assertNull(shadowOf(rig.activity).nextStartedActivity)
            assertTrue(retained.delete())
            assertTrue(obstruction!!.delete())
            fail = false
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
            assertEquals(1, rig.events.size)
        }
    }
}
