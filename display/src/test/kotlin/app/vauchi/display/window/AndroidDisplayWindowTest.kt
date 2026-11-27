// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

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

// Feature: generic_presentation_protocol.feature — native presentation of prepared window controls.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidDisplayWindowTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val main = shadowOf(Looper.getMainLooper())
    private val events = mutableListOf<Event>()
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

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
    fun windowCommandsWithoutAnActivityReportTheirExactUnavailableTransport() {
        val owner = AndroidDisplayExecutor(application) { events.add(it) }.also(owners::add)
        val before = owner.state.value
        assertTrue(owner.execute(Command.SetScreenBrightness(0.65f)))
        assertTrue(owner.execute(Command.SetIdleTimerDisabled(true)))
        assertTrue(owner.execute(Command.SetOrientationLock(Orientation.Portrait)))
        assertEquals(
            listOf(
                Event.HardwareUnavailable("screen_brightness"),
                Event.HardwareUnavailable("idle_timer"),
                Event.HardwareUnavailable("orientation_lock"),
            ),
            events,
        )
        assertEquals(before, owner.state.value)
        assertFalse(owner.execute(Command.BleStopScanning))
        owner.close()
        assertFalse(owner.execute(Command.SetScreenBrightness(null)))
        assertEquals(3, events.size)
    }

    @Test
    fun preparedWindowValuesApplyNativelyAndClosingRestoresOnlyOwnedOverrides() {
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = 0.24f }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val owner =
            AndroidDisplayExecutor(application, windowHost = ActivityWindowHost { activity }) { events.add(it) }
                .also(owners::add)

        assertTrue(owner.execute(Command.SetScreenBrightness(0.65f)))
        assertEquals(0.65f, window.attributes.screenBrightness)
        assertTrue(owner.execute(Command.SetScreenBrightness(0.9f)))
        assertEquals(0.9f, window.attributes.screenBrightness)
        assertTrue(owner.execute(Command.SetIdleTimerDisabled(true)))
        assertEquals(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        assertTrue(owner.execute(Command.SetOrientationLock(Orientation.Portrait)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        assertTrue(owner.execute(Command.SetOrientationLock(Orientation.Landscape)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)

        owner.close()
        main.idle()

        assertEquals(0.24f, window.attributes.screenBrightness)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_BEHIND, activity.requestedOrientation)
        assertEquals(emptyList<Event>(), events)
    }
}
