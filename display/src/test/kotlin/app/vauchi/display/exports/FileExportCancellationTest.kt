// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import app.vauchi.contract.Command
import app.vauchi.contract.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Feature: generic_presentation_protocol.feature — cancellation-safe native export ownership.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FileExportCancellationTest {
    private val rig = ExportTestRig()

    @After
    fun release() = rig.close()

    @Test
    fun ownerCloseInterruptsWriteClosesTheStreamAndRemovesUnpublishedOutput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
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

                        override fun close() {
                            closed.incrementAndGet()
                            super.close()
                        }
                    }
                }),
            )
        runBlocking {
            val operation = async(start = CoroutineStart.UNDISPATCHED) { owner.executeAsync(Command.ExportFile(rig.spec)) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                owner.close()
                try {
                    operation.await()
                    fail("Closed owner must cancel native export")
                } catch (_: CancellationException) {
                }
                assertTrue(operation.isCancelled)
                assertEquals(1, closed.get())
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertEquals(emptyList<Event>(), rig.events)
                assertNull(shadowOf(rig.activity).nextStartedActivity)
                assertFalse(owner.executeAsync(Command.ExportFile(rig.spec)))
            } finally {
                release.countDown()
                operation.cancel()
                operation.join()
            }
        }
        runBlocking { assertTrue(rig.owner().executeAsync(Command.ExportFile(rig.spec))) }
        assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
    }

    @Test
    fun callerCancellationReleasesTheSlotAndKeepsTheOwnerUsable() {
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
                operation.cancel()
                release.countDown()
                try {
                    operation.await()
                    fail("Caller cancellation must propagate")
                } catch (_: CancellationException) {
                }
                assertTrue(operation.isCancelled)
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertNull(shadowOf(rig.activity).nextStartedActivity)
                assertEquals(emptyList<Event>(), rig.events)
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
            } finally {
                release.countDown()
                operation.cancel()
                operation.join()
            }
        }
    }

    @Test
    fun cancellationAfterIoCompletesStillRemovesOutputBeforeAnyUiLaunch() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val owner = rig.owner(rig.host(io = dispatcher))
        try {
            runBlocking {
                val operation = async(start = CoroutineStart.UNDISPATCHED) { owner.executeAsync(Command.ExportFile(rig.spec)) }
                executor.submit {}.get(10, TimeUnit.SECONDS)
                assertEquals(1, rig.directory.listFiles()!!.size)
                assertNull(shadowOf(rig.activity).nextStartedActivity)
                operation.cancel()
                try {
                    operation.await()
                    fail("Canceled IO handoff must not publish")
                } catch (_: CancellationException) {
                }
                assertTrue(operation.isCancelled)
                assertEquals(emptyList<String>(), rig.directory.list()!!.toList())
                assertNull(shadowOf(rig.activity).nextStartedActivity)
                assertEquals(emptyList<Event>(), rig.events)
                assertTrue(owner.executeAsync(Command.ExportFile(rig.spec)))
                assertArrayEquals(byteArrayOf(0, -1, 3), rig.read(rig.takeUri()))
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
