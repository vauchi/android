// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: generic_presentation_protocol.feature — native export admission and publication handoff.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportHandoffTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun slowCanceledNativeWriteRetainsTheGlobalPermitUntilItActuallyReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val first =
            rig.owner(
                rig.host(openOutput = { file ->
                    object : FilterOutputStream(FileOutputStream(file)) {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            entered.countDown()
                            while (release.count > 0) {
                                try {
                                    check(release.await(10, TimeUnit.SECONDS))
                                } catch (_: InterruptedException) {
                                }
                            }
                            out.write(bytes, offset, length)
                        }
                    }
                }),
            )
        val second = rig.owner()
        try {
            runBlocking {
                val operation = async(start = CoroutineStart.UNDISPATCHED) { first.executeAsync(Command.ExportFile(rig.spec)) }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    worker.submit { first.close() }.get(10, TimeUnit.SECONDS)
                    assertTrue(second.executeAsync(Command.ExportFile(rig.spec)))
                    assertEquals(listOf(Event.HardwareError("file_export_busy", "file_export")), rig.events)
                    assertNull(shadowOf(rig.activity).nextStartedActivity)
                    release.countDown()
                    try {
                        operation.await()
                        fail("Closed owner must not publish")
                    } catch (_: CancellationException) {
                    }
                    assertTrue(operation.isCancelled)
                    assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                    assertFalse(first.executeAsync(Command.ExportFile(rig.spec)))
                    assertTrue(second.executeAsync(Command.ExportFile(rig.spec)))
                    assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
                    assertEquals(1, rig.events.size)
                } finally {
                    release.countDown()
                    operation.cancel()
                    operation.join()
                }
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun activityDestructionDuringWriteCannotLaunchOrRetainAnUnpublishedFile() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner =
            rig.owner(
                rig.host(openOutput = { file ->
                    object : FilterOutputStream(FileOutputStream(file)) {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            out.write(bytes, offset, length)
                        }
                    }
                }),
            )
        runBlocking {
            val operation = async(start = CoroutineStart.UNDISPATCHED) { owner.executeAsync(Command.ExportFile(rig.spec)) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                rig.controller
                    .pause()
                    .stop()
                    .destroy()
                release.countDown()
                assertTrue(operation.await())
                assertEquals(listOf(Event.HardwareUnavailable("file_export")), rig.events)
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertNull(shadowOf(rig.activity).nextStartedActivity)
            } finally {
                release.countDown()
                operation.cancel()
                operation.join()
            }
        }
    }

    @Test
    fun closingDuringAcceptedNativeLaunchKeepsTheFileReadableForTheRecipient() {
        val controller = Robolectric.buildActivity(ClosingExportActivity::class.java).setup()
        val activity = controller.get()
        val owner = rig.owner(rig.host(activity = { activity }))
        activity.afterLaunch = owner::close
        try {
            runBlocking {
                val operation = async { owner.executeAsync(Command.ExportFile(rig.spec)) }
                try {
                    operation.await()
                    fail("Owner closure must cancel its operation")
                } catch (_: CancellationException) {
                }
                assertTrue(operation.isCancelled)
                val chooser = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(send.getParcelableExtra(Intent.EXTRA_STREAM)!!))
                assertEquals(1, rig.directory.listFiles()!!.size)
                assertEquals(emptyList<Event>(), rig.events)
                assertFalse(owner.executeAsync(Command.ExportFile(rig.spec)))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

class ClosingExportActivity : ComponentActivity() {
    var afterLaunch: (() -> Unit)? = null

    override fun startActivity(
        intent: Intent,
        options: Bundle?,
    ) {
        super.startActivity(intent, options)
        afterLaunch?.invoke()
    }
}
