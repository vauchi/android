// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: generic_presentation_protocol.feature — native presentation ownership.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ActivityWindowOwnershipTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val main = shadowOf(Looper.getMainLooper())
    private val events = mutableListOf<Event>()
    private val owners = mutableListOf<AndroidDisplayExecutor>()

    private fun owner(provider: () -> ComponentActivity? = { activity }) =
        AndroidDisplayExecutor(application, windowHost = ActivityWindowHost(provider)) { events.add(it) }.also(owners::add)

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
    fun aMainThreadDisplayHandoffReleasesTheOldWindowBeforeTheNewCommand() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val old = owner()
        assertTrue(old.execute(Command.SetScreenBrightness(0.65f)))
        old.close()
        val next = owner()
        assertTrue(next.execute(Command.SetScreenBrightness(0.9f)))
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        main.idle()
        assertEquals(0.9f, activity.window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun closingFromTheActivityProviderCannotAcquireAWindowAfterOwnerClosure() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        lateinit var current: AndroidDisplayExecutor
        current =
            owner {
                current.close()
                activity
            }
        assertTrue(current.execute(Command.SetScreenBrightness(0.65f)))
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        main.idle()
        assertEquals(0.24f, activity.window.attributes.screenBrightness)
        assertFalse(current.execute(Command.SetScreenBrightness(0.9f)))
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun aMainThreadRepeatCloseFinishesPendingWorkerRetirementBeforeHandoff() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.24f }
        val old = owner()
        old.execute(Command.SetScreenBrightness(0.65f))
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { old.close() }.get(10, TimeUnit.SECONDS)
            old.close()
            val next = owner()
            next.execute(Command.SetScreenBrightness(0.9f))
            assertEquals(0.9f, activity.window.attributes.screenBrightness)
            main.idle()
            assertEquals(0.9f, activity.window.attributes.screenBrightness)
            next.close()
            main.idle()
            assertEquals(0.24f, activity.window.attributes.screenBrightness)
            assertEquals(emptyList<Event>(), events)
        } finally {
            worker.shutdownNow()
        }
    }
}
