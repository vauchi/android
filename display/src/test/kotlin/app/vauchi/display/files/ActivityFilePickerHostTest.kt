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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow.extract
import org.robolectric.shadows.ShadowActivity
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: backup_recovery.feature @backup
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], shadows = [PickerActivityShadow::class])
class ActivityFilePickerHostTest {
    private val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = owner.get()
    private val io = Executors.newSingleThreadExecutor()
    private val host = ActivityFilePickerHost({ activity }, io)
    private val answers = mutableListOf<FilePickOutcome>()
    private val pickers = mutableListOf<ActivityController<DocumentPickerActivity>>()
    private val nativeCodes = mutableMapOf<DocumentPickerActivity, Int>()
    private val command = Command.PickFile(listOf("vcf"), listOf("text/vcard"), 4uL, "opaque-id", "Prepared title")

    private fun open(): Pair<DocumentPickerActivity, Intent> {
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(DocumentPickerActivity::class.java.name, launch.component!!.className)
        val picker = Robolectric.buildActivity(DocumentPickerActivity::class.java, launch).setup()
        pickers += picker
        val native = shadowOf(picker.get()).nextStartedActivityForResult
        nativeCodes[picker.get()] = native.requestCode
        return picker.get() to native.intent
    }

    @After
    fun release() {
        host.close()
        io.shutdownNow()
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
        pickers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        if (!activity.isDestroyed) owner.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun preparedFiltersAndTitleReachAnOpenableSystemPicker() {
        host.pick(command, answers::add)
        val (_, native) = open()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, native.action)
        assertEquals("text/vcard", native.type)
        assertEquals(listOf("text/vcard"), native.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
        assertEquals(command.title, native.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(native.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals(emptyList<FilePickOutcome>(), answers)
    }

    @Test
    fun dismissingTheSystemPickerCompletesExactlyOnce() {
        host.pick(command, answers::add)
        val (picker, native) = open()
        shadowOf(picker).receiveResult(native, Activity.RESULT_CANCELED, null)
        assertEquals(listOf(FilePickOutcome.Cancelled), answers)
        shadowOf(picker).receiveResult(native, Activity.RESULT_CANCELED, null)
        assertEquals(listOf(FilePickOutcome.Cancelled), answers)
        assertTrue(picker.isFinishing)
    }

    @Test
    fun selectedContentIsReadOffTheUiThreadBeforeReturningTypedBytes() {
        host.pick(command, answers::add)
        val (picker, native) = open()
        val uri = Uri.parse("content://picked/item")
        var readOnMain = false
        val bytes =
            object : ByteArrayInputStream(byteArrayOf(1, -1)) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    readOnMain = Looper.myLooper() == Looper.getMainLooper()
                    return super.read(buffer, offset, length)
                }
            }
        shadowOf(activity.contentResolver).registerInputStream(uri, bytes)
        shadowOf(picker).receiveResult(native, Activity.RESULT_OK, Intent().setData(uri))
        io.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(readOnMain)
        assertEquals(listOf(FilePickOutcome.Selected(listOf(1u, 255u), "file")), answers)
        assertTrue(picker.isFinishing)
    }

    @Test
    fun closingALeaseDismissesTheNativeChildAndDropsLateResults() {
        val lease = host.pick(command, answers::add)
        val (picker, native) = open()
        lease.close()
        assertTrue(picker.isFinishing)
        assertEquals(listOf(nativeCodes[picker]), extract<PickerActivityShadow>(picker).dismissed)
        shadowOf(picker).receiveResult(native, Activity.RESULT_OK, Intent().setData(Uri.parse("content://stale/item")))
        assertEquals(emptyList<FilePickOutcome>(), answers)
        host.pick(command.copy(requestId = "replacement"), answers::add)
        val (replacement, next) = open()
        shadowOf(replacement).receiveResult(next, Activity.RESULT_CANCELED, null)
        assertEquals(listOf(FilePickOutcome.Cancelled), answers)
    }

    @Test
    fun ownerDestructionFinishesThePickerWithAFixedFailure() {
        host.pick(command, answers::add)
        val (picker, _) = open()
        owner.pause().stop().destroy()
        assertTrue(picker.isFinishing)
        assertEquals(listOf(FilePickOutcome.Failed(FilePickFailure.ReadFailed)), answers)
    }

    @Test
    fun closedHostsCannotLaunchAnotherPicker() {
        host.close()
        host.pick(command, answers::add)
        assertEquals(listOf(FilePickOutcome.Failed(FilePickFailure.ReadFailed)), answers)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun aCanceledRequestCannotLaunchItsProxyLater() {
        host.pick(command, answers::add).close()
        val launch = shadowOf(activity).nextStartedActivity
        val picker = Robolectric.buildActivity(DocumentPickerActivity::class.java, launch).setup()
        pickers += picker
        assertTrue(picker.get().isFinishing)
        assertEquals(null, shadowOf(picker.get()).nextStartedActivity)
        assertEquals(emptyList<FilePickOutcome>(), answers)
    }

    @Test
    fun cancellationClosesAnAcquiredStreamEvenWhenItIgnoresInterruption() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream =
            object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    while (closed.count > 0) {
                        try {
                            if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("test read timed out")
                        } catch (_: InterruptedException) {
                            // Native file streams may keep reading after Future.cancel(true).
                        }
                    }
                    return -1
                }

                override fun close() {
                    closed.countDown()
                }
            }
        val uri = Uri.parse("content://picked/blocked")
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        val lease = host.pick(command, answers::add)
        val (picker, native) = open()
        shadowOf(picker).receiveResult(native, Activity.RESULT_OK, Intent().setData(uri))
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            lease.close()
            assertTrue("cancel must close the native stream", closed.await(1, TimeUnit.SECONDS))
            io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<FilePickOutcome>(), answers)
        } finally {
            stream.close()
        }
    }

    @Test
    fun nativeFiltersCannotChangeAfterTheirRequestIsAccepted() {
        val filters = mutableListOf("text/vcard")
        host.pick(command.copy(acceptedMimeTypes = filters), answers::add)
        filters[0] = "image/png"
        val (_, native) = open()
        assertEquals("text/vcard", native.type)
        assertEquals(listOf("text/vcard"), native.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
    }
}

@Implements(Activity::class)
class PickerActivityShadow : ShadowActivity() {
    val dismissed = mutableListOf<Int>()

    @Implementation
    fun finishActivity(requestCode: Int) {
        dismissed += requestCode
    }
}
