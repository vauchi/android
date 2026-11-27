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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

// Feature: generic_presentation_protocol.feature — restore prepared native window effects.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class WindowControlRestorationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val window = activity.window
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
    fun repeatedSetsAndRestoreCyclesRetainEachFirstNativeSnapshot() {
        window.attributes = window.attributes.apply { screenBrightness = 0.24f }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val owner = owner()
        owner.execute(Command.SetScreenBrightness(0.65f))
        owner.execute(Command.SetScreenBrightness(0.9f))
        owner.execute(Command.SetScreenBrightness(null))
        assertEquals(0.24f, window.attributes.screenBrightness)
        owner.execute(Command.SetOrientationLock(Orientation.Portrait))
        owner.execute(Command.SetOrientationLock(Orientation.Landscape))
        owner.execute(Command.SetOrientationLock(null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_BEHIND, activity.requestedOrientation)

        window.attributes = window.attributes.apply { screenBrightness = 0.42f }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        owner.execute(Command.SetScreenBrightness(0.8f))
        owner.execute(Command.SetOrientationLock(Orientation.Landscape))
        owner.execute(Command.SetScreenBrightness(null))
        owner.execute(Command.SetOrientationLock(null))
        assertEquals(0.42f, window.attributes.screenBrightness)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)

        window.attributes = window.attributes.apply { screenBrightness = 0.51f }
        owner.close()
        main.idle()
        assertEquals(0.51f, window.attributes.screenBrightness)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, activity.requestedOrientation)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun restoreWithoutAnOverrideUsesDefaultsAndDoesNotReserveTheWindow() {
        window.attributes = window.attributes.apply { screenBrightness = 0.24f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        val old = owner()
        old.execute(Command.SetScreenBrightness(null))
        old.execute(Command.SetOrientationLock(null))
        old.execute(Command.SetIdleTimerDisabled(false))
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val next = owner()
        next.execute(Command.SetScreenBrightness(0.8f))
        old.close()
        main.idle()
        assertEquals(0.8f, window.attributes.screenBrightness)
        next.close()
        main.idle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, window.attributes.screenBrightness)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun outstandingKeepAwakeRestoresItsPriorBitButExplicitClearEndsOwnership() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        val old = owner()
        old.execute(Command.SetIdleTimerDisabled(true))
        old.execute(Command.SetIdleTimerDisabled(true))
        old.close()
        main.idle()
        assertEquals(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        val next = owner()
        next.execute(Command.SetIdleTimerDisabled(true))
        next.execute(Command.SetIdleTimerDisabled(false))
        next.close()
        main.idle()
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun nonFiniteBrightnessIsRejectedBeforeNativeAcquisitionAndFiniteValuesAreClamped() {
        var lookups = 0
        window.attributes = window.attributes.apply { screenBrightness = 0.24f }
        val owner =
            owner {
                lookups++
                activity
            }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(owner.execute(Command.SetScreenBrightness(value)))
        }
        assertEquals(0, lookups)
        assertEquals(0.24f, window.attributes.screenBrightness)
        assertEquals(List(3) { Event.HardwareError("window_invalid_brightness", "screen_brightness") }, events)
        for ((value, expected) in listOf(-Float.MAX_VALUE to 0f, -1f to 0f, 0f to 0f, 0.65f to 0.65f, 1f to 1f, Float.MAX_VALUE to 1f)) {
            assertTrue(owner.execute(Command.SetScreenBrightness(value)))
            assertEquals(expected, window.attributes.screenBrightness)
        }
        owner.close()
        main.idle()
        assertEquals(0.24f, window.attributes.screenBrightness)
    }

    @Test
    fun randomOutstandingOverrideHistoriesAlwaysRestoreTheOriginalNativeValues() {
        val random = Random(773)
        repeat(40) {
            window.attributes = window.attributes.apply { screenBrightness = 0.24f }
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
            val owner = owner()
            repeat(30) {
                when (random.nextInt(3)) {
                    0 -> {
                        owner.execute(Command.SetScreenBrightness(random.nextFloat() * 4f - 2f))
                        assertTrue(window.attributes.screenBrightness in 0f..1f)
                    }

                    1 -> {
                        owner.execute(Command.SetIdleTimerDisabled(true))
                    }

                    else -> {
                        owner.execute(Command.SetOrientationLock(if (random.nextBoolean()) Orientation.Portrait else Orientation.Landscape))
                    }
                }
            }
            owner.close()
            main.idle()
            assertEquals(0.24f, window.attributes.screenBrightness)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_BEHIND, activity.requestedOrientation)
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        assertEquals(emptyList<Event>(), events)
    }
}
