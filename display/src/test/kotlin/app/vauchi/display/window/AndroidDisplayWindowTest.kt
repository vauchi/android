// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import android.app.Application
import android.os.Looper
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

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
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
}
