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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: generic_presentation_protocol.feature — Activity and display lifetime fencing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WindowLifecycleTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()
    private val activity = newActivity()
    private val main = shadowOf(Looper.getMainLooper())
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private val ports = mutableListOf<AndroidWindowPort>()
    private val events = mutableListOf<Event>()

    private fun newActivity(): ComponentActivity =
        Robolectric
            .buildActivity(ComponentActivity::class.java)
            .setup()
            .also(controllers::add)
            .get()

    private fun owner(provider: () -> ComponentActivity? = { activity }) =
        AndroidDisplayExecutor(application, windowHost = ActivityWindowHost(provider)) { events.add(it) }.also(owners::add)

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
        ports.forEach(AndroidWindowPort::close)
        main.idle()
        for (controller in controllers) {
            val activity = controller.get()
            if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            if (!activity.isDestroyed) controller.destroy()
        }
        main.idle()
    }

    @Test
    fun destroyingAnActivityRestoresOutstandingValuesAndRefusesItsStaleTarget() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val owner = owner()
        owner.execute(Command.SetScreenBrightness(0.65f))
        owner.execute(Command.SetIdleTimerDisabled(true))
        owner.execute(Command.SetOrientationLock(Orientation.Portrait))
        controllers
            .first()
            .pause()
            .stop()
            .destroy()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_BEHIND, activity.requestedOrientation)
        assertTrue(owner.execute(Command.SetIdleTimerDisabled(true)))
        assertEquals(listOf(Event.HardwareUnavailable("idle_timer")), events)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun replacingTheActivityRetiresTheOldWindowBeforeApplyingToTheNewWindow() {
        val nextActivity = newActivity()
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        nextActivity.window.attributes = nextActivity.window.attributes.apply { screenBrightness = 0.42f }
        var current = activity
        val owner = owner { current }
        owner.execute(Command.SetScreenBrightness(0.65f))
        current = nextActivity
        owner.execute(Command.SetScreenBrightness(0.9f))
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(0.9f, nextActivity.window.attributes.screenBrightness)
        owner.close()
        main.idle()
        assertEquals(0.42f, nextActivity.window.attributes.screenBrightness)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun liveHostsCannotOverlapUntilTheExistingOverrideIsExplicitlyRestored() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val old = owner()
        val next = owner()
        old.execute(Command.SetScreenBrightness(0.65f))
        next.execute(Command.SetScreenBrightness(0.9f))
        assertEquals(0.65f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareUnavailable("screen_brightness")), events)
        old.execute(Command.SetScreenBrightness(null))
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        next.execute(Command.SetScreenBrightness(0.9f))
        old.close()
        main.idle()
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(listOf(Event.HardwareUnavailable("screen_brightness")), events)
    }

    @Test
    fun independentWindowsCanBeOwnedSimultaneouslyWithoutCrossRestoration() {
        val otherActivity = newActivity()
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        otherActivity.window.attributes = otherActivity.window.attributes.apply { screenBrightness = 0.42f }
        val first = owner()
        val second = owner { otherActivity }
        first.execute(Command.SetScreenBrightness(0.65f))
        second.execute(Command.SetScreenBrightness(0.9f))
        first.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(0.9f, otherActivity.window.attributes.screenBrightness)
        second.close()
        main.idle()
        assertEquals(0.42f, otherActivity.window.attributes.screenBrightness)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun workerCommandsCannotTouchTheWindowAndWorkerCloseRestoresOnTheMainOwner() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val owner = owner()
        owner.execute(Command.SetScreenBrightness(0.65f))
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(true, worker.submit<Boolean> { owner.execute(Command.SetScreenBrightness(0.9f)) }.get(10, TimeUnit.SECONDS))
            assertEquals(0.65f, activity.window.attributes.screenBrightness)
            assertEquals(listOf(Event.HardwareUnavailable("screen_brightness")), events)
            worker.submit { owner.close() }.get(10, TimeUnit.SECONDS)
            assertFalse(owner.execute(Command.SetScreenBrightness(0.9f)))
            main.idle()
            assertEquals(0.24f, activity.window.attributes.screenBrightness)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun aDirectPortWorkerCloseRefusesBeforeFencingItsMainThreadCleanup() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val port = AndroidWindowPort(ActivityWindowHost { activity }, events::add).also(ports::add)
        port.execute(Command.SetScreenBrightness(0.65f))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val failure =
                worker
                    .submit<Exception?> {
                        try {
                            port.close()
                            null
                        } catch (error: Exception) {
                            error
                        }
                    }.get(10, TimeUnit.SECONDS)
            assertEquals(IllegalStateException::class.java, failure?.javaClass)
            assertEquals("Window cleanup requires main thread", failure?.message)
            assertEquals(0.65f, activity.window.attributes.screenBrightness)
            port.close()
            assertEquals(0.24f, activity.window.attributes.screenBrightness)
            assertEquals(emptyList<Event>(), events)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun aMissingOrFinishingActivityProducesOnlyFixedUnavailableObservations() {
        var target: ComponentActivity? = null
        val owner = owner { target }
        owner.execute(Command.SetScreenBrightness(0.65f))
        target = activity
        activity.finish()
        owner.execute(Command.SetIdleTimerDisabled(true))
        owner.execute(Command.SetOrientationLock(Orientation.Portrait))
        assertEquals(
            listOf(
                Event.HardwareUnavailable("screen_brightness"),
                Event.HardwareUnavailable("idle_timer"),
                Event.HardwareUnavailable("orientation_lock"),
            ),
            events,
        )
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun refusingAnUnavailableObservationClosesAndRestoresTheExistingDisplay() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        var target: ComponentActivity? = activity
        val offered = mutableListOf<Event>()
        val owner =
            AndroidDisplayExecutor(application, windowHost = ActivityWindowHost { target }) {
                offered.add(it)
                false
            }.also(owners::add)
        owner.execute(Command.SetScreenBrightness(0.65f))
        target = null
        owner.execute(Command.SetIdleTimerDisabled(true))
        assertEquals(listOf(Event.HardwareUnavailable("idle_timer")), offered)
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertFalse(owner.execute(Command.SetScreenBrightness(0.9f)))
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
    }
}
