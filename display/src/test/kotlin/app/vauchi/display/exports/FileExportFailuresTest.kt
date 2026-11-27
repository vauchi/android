// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException

// Feature: generic_presentation_protocol.feature — safe native export failures.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportFailuresTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun absentFinishingDestroyedAndClosedActivitiesReportUnavailable() {
        val missing = rig.owner(rig.host(activity = { null }))
        val closed = rig.host().also(ActivityFileExportHost::close)
        runBlocking {
            assertTrue(missing.executeAsync(Command.ExportFile(rig.spec)))
            assertTrue(rig.owner(closed).executeAsync(Command.ExportFile(rig.spec)))
            rig.activity.finish()
            assertTrue(rig.owner().executeAsync(Command.ExportFile(rig.spec)))
            rig.controller
                .pause()
                .stop()
                .destroy()
            assertTrue(rig.owner().executeAsync(Command.ExportFile(rig.spec)))
        }
        assertEquals(List(4) { Event.HardwareUnavailable("file_export") }, rig.events)
        assertEquals(false, rig.directory.exists())
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun throwingOrReentrantProviderCannotAcquireFilesOrExposeDetails() {
        val failure = rig.owner(rig.host(activity = { throw SecurityException("private-path?secret=token") }))
        lateinit var owner: app.vauchi.display.AndroidDisplayExecutor
        owner =
            rig.owner(
                rig.host(activity = {
                    owner.close()
                    rig.activity
                }),
            )
        runBlocking {
            assertTrue(failure.executeAsync(Command.ExportFile(rig.spec)))
            assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
            assertFalse(owner.executeAsync(Command.ExportFile(rig.spec)))
        }
        assertEquals(listOf(Event.HardwareUnavailable("file_export")), rig.events)
        assertEquals(false, rig.directory.exists())
        assertNull(shadowOf(rig.activity).nextStartedActivity)
    }

    @Test
    fun writeAndCloseFailuresRemovePartialFilesWithoutCancelingTheCaller() {
        for (failOnClose in listOf(false, true)) {
            var fail = true
            val host =
                rig.host(openOutput = { file ->
                    if (fail && !failOnClose) throw IOException("private write diagnostic")
                    object : FilterOutputStream(FileOutputStream(file)) {
                        override fun close() {
                            super.close()
                            if (fail) throw IOException("private close diagnostic")
                        }
                    }
                })
            val owner = rig.owner(host)
            val previousFiles = rig.directory.list()?.toSet() ?: emptySet()
            runBlocking {
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                assertEquals(true, currentCoroutineContext()[Job]!!.isActive)
                assertEquals(previousFiles, rig.directory.list()?.toSet() ?: emptySet<String>())
                assertNull(shadowOf(rig.activity).nextStartedActivity)
                fail = false
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
            }
        }
        assertEquals(List(2) { Event.HardwareError("file_export_failed", "file_export") }, rig.events)
    }

    @Test
    fun nativeLaunchRefusalRemovesUnpublishedFileAndAllowsLaterRetry() {
        val controller = Robolectric.buildActivity(ExportLaunchFailureActivity::class.java).setup()
        val activity = controller.get()
        val owner = rig.owner(rig.host(activity = { activity }))
        try {
            runBlocking {
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                assertEquals(listOf(Event.HardwareError("file_export_failed", "file_export")), rig.events)
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertNull(shadowOf(activity).nextStartedActivity)
                activity.reject = false
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                val chooser = shadowOf(activity).nextStartedActivity
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(send.getParcelableExtra(Intent.EXTRA_STREAM)!!))
                assertEquals(1, rig.events.size)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun workerMisuseDoesNotAcquireActivityAndMainCanRetry() {
        var acquired = 0
        val owner =
            rig.owner(
                rig.host(activity = {
                    acquired++
                    rig.activity
                }),
            )
        runBlocking(Dispatchers.Default) { assertTrue(owner.executeAsync(Command.ExportFile(rig.spec))) }
        assertEquals(0, acquired)
        assertEquals(listOf(Event.HardwareUnavailable("file_export")), rig.events)
        runBlocking { assertTrue(owner.executeAsync(Command.ExportFile(rig.spec))) }
        assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
        assertEquals(1, rig.events.size)
    }
}

class ExportLaunchFailureActivity : ComponentActivity() {
    var reject = true

    override fun startActivity(
        intent: Intent,
        options: Bundle?,
    ) {
        if (reject) throw ActivityNotFoundException("private native diagnostic")
        super.startActivity(intent, options)
    }
}
