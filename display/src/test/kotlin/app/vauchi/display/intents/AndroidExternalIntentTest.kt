// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.intents

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.AndroidDisplayExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — native execution of prepared external intents.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidExternalIntentTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun missingHostReportsExactNativeFailuresWithoutRetainingAnEffect() {
        val events = mutableListOf<Event>()
        val owner = AndroidDisplayExecutor(application) { events.add(it) }
        val before = owner.state.value
        try {
            assertTrue(owner.execute(Command.OpenExternalUrl("https://example.test/private")))
            assertTrue(owner.execute(Command.ShowShareSheet("https://example.test/token")))
            assertEquals(
                listOf(Event.HardwareUnavailable("external_url"), Event.HardwareUnavailable("share_sheet")),
                events,
            )
            assertEquals(before, owner.state.value)
            assertFalse(owner.execute(Command.BleStopScanning))
            owner.close()
            assertFalse(owner.execute(Command.OpenExternalUrl("https://example.test/closed")))
            assertEquals(2, events.size)
        } finally {
            owner.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
