// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.AndroidDisplayExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: contact_exchange.feature @exchange
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidDisplayPermissionTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val native = shadowOf(activity)
    private val main = shadowOf(Looper.getMainLooper())
    private val host = ActivityPermissionHost { activity }
    private val events = mutableListOf<Event>()
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private var accept = true

    private fun owner(permissionHost: PermissionRequestHost? = host) =
        AndroidDisplayExecutor(
            application,
            permissionHost = permissionHost,
        ) { if (accept) events.add(it) else false }.also(owners::add)

    @Before
    fun denyNativePermissions() = shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
        main.idle()
        if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        if (!activity.isDestroyed) controller.destroy()
        main.idle()
    }

    @Test
    fun theSharedOwnerRoutesARealActivityAnswerThroughCanonicalAdmission() {
        assertTrue(owner().execute(Command.RequestPermission("camera")))
        val pending = native.lastRequestedPermission
        assertEquals(listOf(Manifest.permission.CAMERA), pending.requestedPermissions.toList())
        assertEquals(emptyList<Event>(), events)
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        activity.answerPermissions(pending)
        assertEquals(listOf(Event.PermissionGranted("camera")), events)
    }

    @Test
    fun osResultLabelsCannotGrantAccessThatTheNativePermissionStateStillDenies() {
        assertTrue(owner().execute(Command.RequestPermission("camera")))
        activity.answerPermissions(native.lastRequestedPermission)
        assertEquals(listOf(Event.PermissionDenied("camera")), events)
    }

    @Test
    fun closingBeforeAnOsAnswerPreventsAdmissionAndFurtherRequests() {
        val executor = owner()
        assertTrue(executor.execute(Command.RequestPermission("camera")))
        val pending = native.lastRequestedPermission
        executor.close()
        main.idle()
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        activity.answerPermissions(pending)
        assertEquals(emptyList<Event>(), events)
        assertFalse(executor.execute(Command.RequestPermission("microphone")))
    }

    @Test
    fun aCanceledOsPromptCannotBeReplacedOrLeakIntoAnotherOwner() {
        val old = owner()
        assertTrue(old.execute(Command.RequestPermission("camera")))
        val pending = native.lastRequestedPermission
        old.close()
        main.idle()
        val next = owner()
        assertTrue(next.execute(Command.RequestPermission("microphone")))
        assertEquals(listOf(Event.HardwareUnavailable("microphone")), events)
        assertEquals(pending.requestCode, native.lastRequestedPermission.requestCode)
        activity.answerPermissions(pending)
        main.idle()
        assertEquals(listOf(Event.HardwareUnavailable("microphone")), events)
        assertTrue(next.execute(Command.RequestPermission("microphone")))
        val replacement = native.lastRequestedPermission
        assertTrue(replacement.requestCode != pending.requestCode)
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        activity.answerPermissions(replacement)
        assertEquals(listOf(Event.HardwareUnavailable("microphone"), Event.PermissionGranted("microphone")), events)
    }

    @Test
    fun activityDestructionReportsHostLossWithoutStrandingTheRoot() {
        val executor = owner()
        assertTrue(executor.execute(Command.RequestPermission("camera")))
        controller.pause().stop().destroy()
        assertEquals(listOf(Event.HardwareUnavailable("camera")), events)
        assertTrue(executor.execute(Command.CameraViewport("still-owned")))
        assertEquals("still-owned", executor.state.value.previewId)
    }

    @Test
    fun refusingThePermissionObservationClosesNativeAdmission() {
        val executor = owner()
        assertTrue(executor.execute(Command.RequestPermission("camera")))
        accept = false
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        activity.answerPermissions(native.lastRequestedPermission)
        assertEquals(emptyList<Event>(), events)
        assertFalse(executor.execute(Command.CameraViewport("not-acquired")))
    }

    @Test
    fun anUnhostedOwnerReportsUnavailableRatherThanSilentlyDroppingTheCommand() {
        assertTrue(owner(permissionHost = null).execute(Command.RequestPermission("camera")))
        assertEquals(listOf(Event.HardwareUnavailable("camera")), events)
        assertEquals(null, native.lastRequestedPermission)
    }

    @Test
    fun renderingAndPermissionRequestsUseTheSameDisplayOwner() {
        val executor = owner()
        assertTrue(executor.execute(Command.CameraViewport("opaque-preview")))
        assertEquals("opaque-preview", executor.state.value.previewId)
        assertTrue(executor.execute(Command.RequestPermission("microphone")))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), native.lastRequestedPermission.requestedPermissions.toList())
        activity.answerPermissions(native.lastRequestedPermission)
        assertEquals(listOf(Event.PermissionDenied("microphone")), events)
    }
}
