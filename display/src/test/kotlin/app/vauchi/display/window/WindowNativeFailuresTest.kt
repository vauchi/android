// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import android.Manifest
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.Orientation
import app.vauchi.display.AndroidDisplayExecutor
import app.vauchi.display.permissions.PermissionRequestHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — native presentation failure observations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WindowNativeFailuresTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val controller = Robolectric.buildActivity(WindowFailureActivity::class.java).setup()
    private val activity = controller.get()
    private val main = shadowOf(Looper.getMainLooper())
    private val events = mutableListOf<Event>()
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private val ports = mutableListOf<AndroidWindowPort>()

    private fun owner(provider: () -> ComponentActivity? = { activity }) =
        AndroidDisplayExecutor(application, windowHost = ActivityWindowHost(provider)) { events.add(it) }.also(owners::add)

    @After
    fun release() {
        activity.rejectOrientation = false
        owners.forEach(AndroidDisplayExecutor::close)
        ports.forEach(AndroidWindowPort::close)
        main.idle()
        if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        if (!activity.isDestroyed) controller.destroy()
        main.idle()
    }

    @Test
    fun aNativeSetterFailureRollsBackOtherOverridesAndReleasesWindowOwnership() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val old = owner()
        old.execute(Command.SetScreenBrightness(0.65f))
        old.execute(Command.SetIdleTimerDisabled(true))
        activity.rejectOrientation = true
        assertTrue(old.execute(Command.SetOrientationLock(Orientation.Portrait)))
        assertEquals(listOf(Event.HardwareUnavailable("orientation_lock")), events)
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_BEHIND, activity.requestedOrientation)
        activity.rejectOrientation = false
        val next = owner()
        next.execute(Command.SetScreenBrightness(0.9f))
        old.close()
        main.idle()
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareUnavailable("orientation_lock")), events)
    }

    @Test
    fun aRestorationFailureStillCleansOtherPropertiesAndReportsOnlyAFixedError() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val port = AndroidWindowPort(ActivityWindowHost { activity }, events::add).also(ports::add)
        port.execute(Command.SetScreenBrightness(0.65f))
        port.execute(Command.SetIdleTimerDisabled(true))
        port.execute(Command.SetOrientationLock(Orientation.Portrait))
        activity.rejectOrientation = true
        port.close()
        assertEquals(listOf(Event.HardwareError("window_restore_failed", "window")), events)
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        activity.rejectOrientation = false
        val next = owner()
        next.execute(Command.SetScreenBrightness(0.9f))
        port.close()
        assertFalse(port.execute(Command.SetScreenBrightness(0.3f)))
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareError("window_restore_failed", "window")), events)
    }

    @Test
    fun privateProviderFailuresRetireExistingOverridesWithoutLeakingTheirCause() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        var reject = false
        val owner =
            owner {
                if (reject) throw SecurityException("private native Activity detail")
                activity
            }
        owner.execute(Command.SetScreenBrightness(0.65f))
        reject = true
        assertTrue(owner.execute(Command.SetIdleTimerDisabled(true)))
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareUnavailable("idle_timer")), events)
        reject = false
        owner.execute(Command.SetScreenBrightness(0.9f))
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        owner.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareUnavailable("idle_timer")), events)
    }

    @Test
    fun aFailingPermissionLeaseCannotCrashOrPreventWindowCleanup() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val permissionHost =
            PermissionRequestHost { _, _, _ ->
                AutoCloseable { throw IllegalStateException("private native permission detail") }
            }
        val old =
            AndroidDisplayExecutor(
                application,
                permissionHost = permissionHost,
                windowHost = ActivityWindowHost { activity },
            ) { events.add(it) }.also(owners::add)
        assertTrue(old.execute(Command.RequestPermission("camera")))
        old.execute(Command.SetScreenBrightness(0.65f))
        old.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertFalse(old.execute(Command.RequestPermission("camera")))
        val next = owner()
        next.execute(Command.SetScreenBrightness(0.9f))
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(emptyList<Event>(), events)
    }
}

/** Only the Android boundary rejects a write; the actual window and executor stay real. */
class WindowFailureActivity : ComponentActivity() {
    var rejectOrientation = false

    override fun setRequestedOrientation(requestedOrientation: Int) {
        if (rejectOrientation) throw SecurityException("private native orientation detail")
        super.setRequestedOrientation(requestedOrientation)
    }
}
