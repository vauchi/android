// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import app.vauchi.contract.Command
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Feature: backup_recovery.feature @backup
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], shadows = [PickerActivityShadow::class])
class NativeFilePickerCancellationTest {
    private val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val host = ActivityFilePickerHost { owner.get() }
    private val pickers = mutableListOf<ActivityController<DocumentPickerActivity>>()
    private val answers = mutableListOf<FilePickOutcome>()
    private val command = Command.PickFile(emptyList(), emptyList(), 4uL, "id", "Core title")

    private fun select(uri: Uri): DocumentPickerActivity {
        val launch = shadowOf(owner.get()).nextStartedActivity
        val picker = Robolectric.buildActivity(DocumentPickerActivity::class.java, launch).setup()
        pickers += picker
        val intent = shadowOf(picker.get()).nextStartedActivity
        shadowOf(picker.get()).receiveResult(intent, Activity.RESULT_OK, Intent().setData(uri))
        return picker.get()
    }

    @After
    fun release() {
        host.close()
        pickers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        owner.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun providerStreamDisposalCannotBlockNativeUiCancellation() {
        val started = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val closedOnMain = AtomicBoolean(false)
        val stream =
            object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    awaitIgnoringInterruption(closing)
                    return -1
                }

                override fun close() {
                    if (Looper.myLooper() == Looper.getMainLooper()) closedOnMain.set(true)
                    closing.countDown()
                    awaitIgnoringInterruption(released)
                    disposed.countDown()
                }
            }
        val uri = Uri.parse("content://picked/slow-close")
        shadowOf(owner.get().contentResolver).registerInputStream(uri, stream)
        val lease = host.pick(command, answers::add)
        val picker = select(uri)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            lease.close()
            assertTrue(picker.isFinishing)
            assertTrue(closing.await(10, TimeUnit.SECONDS))
            assertFalse("provider disposal must run off the owner thread", closedOnMain.get())
            assertEquals("native UI must finish before provider disposal returns", 1L, disposed.count)
            assertEquals(emptyList<FilePickOutcome>(), answers)
        } finally {
            released.countDown()
            stream.close()
            assertTrue(disposed.await(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun anUnresponsiveReadCannotQueueMoreWorkBehindTheProductionWorker() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val stream =
            object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    awaitIgnoringInterruption(released)
                    returned.countDown()
                    return -1
                }

                override fun close() = Unit
            }
        val uri = Uri.parse("content://picked/unresponsive")
        shadowOf(owner.get().contentResolver).registerInputStream(uri, stream)
        val lease = host.pick(command, answers::add)
        select(uri)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            lease.close()
            host.pick(command.copy(requestId = "next"), answers::add)
            val next = select(uri)
            assertTrue(next.isFinishing)
            assertEquals(listOf(FilePickOutcome.Failed(FilePickFailure.ReadFailed)), answers)
        } finally {
            released.countDown()
            assertTrue(returned.await(10, TimeUnit.SECONDS))
        }
    }

    private fun awaitIgnoringInterruption(latch: CountDownLatch) {
        while (latch.count > 0) {
            try {
                check(latch.await(3, TimeUnit.SECONDS)) { "test provider timed out" }
            } catch (_: InterruptedException) {
                // Exercise a provider that cannot be stopped by Future.cancel(true).
            }
        }
    }
}
