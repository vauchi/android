// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import app.vauchi.display.AndroidDisplayExecutor
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
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: backup_recovery.feature @backup
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], shadows = [PickerActivityShadow::class])
class AndroidDisplayFilePickTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val io = Executors.newSingleThreadExecutor()
    private val native = ActivityFilePickerHost({ activity.get() }, io)
    private val events = mutableListOf<Event>()
    private val display = AndroidDisplayExecutor(activity.get(), filePickerHost = native) { events.add(it) }
    private val pickers = mutableListOf<ActivityController<DocumentPickerActivity>>()
    private val command = Command.PickFile(emptyList(), emptyList(), 4uL, "opaque-id", "Core title")

    private fun open(): Pair<DocumentPickerActivity, Intent> {
        val launch = shadowOf(activity.get()).nextStartedActivity
        val picker = Robolectric.buildActivity(DocumentPickerActivity::class.java, launch).setup()
        pickers += picker
        return picker.get() to shadowOf(picker.get()).nextStartedActivity
    }

    @After
    fun release() {
        display.close()
        shadowOf(Looper.getMainLooper()).idle()
        io.shutdownNow()
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
        pickers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        activity.pause().stop().destroy()
    }

    @Test
    fun availabilityIsExplicitAndAUserCancelKeepsTheOpaqueCorrelation() {
        display.publishCapabilities()
        assertEquals(listOf(Event.FilePickerCapabilitiesChanged(true)), events)
        assertTrue(display.execute(command))
        val (picker, intent) = open()
        assertEquals("*/*", intent.type)
        shadowOf(picker).receiveResult(intent, Activity.RESULT_CANCELED, null)
        assertEquals(
            listOf(Event.FilePickerCapabilitiesChanged(true), Event.FilePickCompleted(FilePickOutcome.Cancelled, "opaque-id")),
            events,
        )
    }

    @Test
    fun selectedContentEnforcesTheCoreBudgetAndReturnsATypedFailure() {
        assertTrue(display.execute(command))
        val (picker, intent) = open()
        val uri = Uri.parse("content://picked/too-large")
        shadowOf(activity.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(5)))
        shadowOf(picker).receiveResult(intent, Activity.RESULT_OK, Intent().setData(uri))
        io.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Event.FilePickCompleted(FilePickOutcome.Failed(FilePickFailure.TooLarge), "opaque-id")), events)
    }

    @Test
    fun cancelingTheWrongIdCannotDismissTheCurrentNativeRequest() {
        display.execute(command)
        val (picker, intent) = open()
        assertTrue(display.execute(Command.CancelFilePick("different")))
        assertFalse(picker.isFinishing)
        assertTrue(display.execute(Command.CancelFilePick(command.requestId)))
        assertTrue(picker.isFinishing)
        shadowOf(picker).receiveResult(intent, Activity.RESULT_CANCELED, null)
        assertEquals(emptyList<Event>(), events)
        assertTrue(display.execute(command.copy(requestId = "replacement")))
        val (next, request) = open()
        shadowOf(next).receiveResult(request, Activity.RESULT_CANCELED, null)
        assertEquals(listOf(Event.FilePickCompleted(FilePickOutcome.Cancelled, "replacement")), events)
    }

    @Test
    fun duplicateCommandsCannotCompleteThePendingOperationOrOpenAnotherPicker() {
        display.execute(command)
        val (picker, intent) = open()
        display.execute(command)
        assertEquals(emptyList<Event>(), events)
        assertEquals(null, shadowOf(activity.get()).nextStartedActivity)
        display.execute(command.copy(requestId = "busy"))
        assertEquals(listOf(Event.FilePickCompleted(FilePickOutcome.Failed(FilePickFailure.InvalidRequest), "busy")), events)
        shadowOf(picker).receiveResult(intent, Activity.RESULT_CANCELED, null)
        assertEquals(Event.FilePickCompleted(FilePickOutcome.Cancelled, "opaque-id"), events.last())
    }

    @Test
    fun ownerClosureDismissesNativeUiAndFencesItsLateResult() {
        display.execute(command)
        val (picker, intent) = open()
        display.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(picker.isFinishing)
        shadowOf(picker).receiveResult(intent, Activity.RESULT_CANCELED, null)
        assertEquals(emptyList<Event>(), events)
        assertFalse(display.execute(command))
    }

    @Test
    fun missingNativeHostingReportsUnavailableWithoutAdvertisingSupport() {
        AndroidDisplayExecutor(activity.get()) { events.add(it) }.use { unhosted ->
            unhosted.publishCapabilities()
            assertTrue(unhosted.execute(command))
            assertEquals(
                listOf(
                    Event.FilePickerCapabilitiesChanged(false),
                    Event.FilePickCompleted(FilePickOutcome.Failed(FilePickFailure.ReadFailed), "opaque-id"),
                ),
                events,
            )
        }
    }

    @Test
    fun idsThatCannotCrossTheCanonicalEventBoundaryAreNeverEchoed() {
        for (id in listOf("", "x".repeat(129), "é".repeat(65))) {
            assertTrue(display.execute(command.copy(requestId = id)))
        }
        assertEquals(List(3) { Event.HardwareError("file_pick_invalid_request", "file_picker") }, events)
        assertEquals(null, shadowOf(activity.get()).nextStartedActivity)
    }
}
