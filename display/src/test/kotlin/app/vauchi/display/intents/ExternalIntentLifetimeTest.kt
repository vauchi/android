// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.intents

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.AndroidDisplayExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: generic_presentation_protocol.feature — external intent owner lifetime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ExternalIntentLifetimeTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val main = shadowOf(Looper.getMainLooper())
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val owners = mutableListOf<AndroidDisplayExecutor>()

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
        main.idle()
        controller.pause().stop().destroy()
        main.idle()
    }

    @Test
    fun providerFollowsReplacementAndRejectsItsDestroyedActivity() {
        val replacementController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var current = activity
        val events = mutableListOf<Event>()
        val owner =
            AndroidDisplayExecutor(application, externalIntentHost = ActivityExternalIntentHost { current }) { events.add(it) }
                .also(owners::add)
        assertTrue(owner.execute(Command.OpenExternalUrl("https://example.test/first")))
        assertEquals("https://example.test/first", shadowOf(activity).nextStartedActivity.data.toString())
        current = replacementController.get()
        assertTrue(owner.execute(Command.OpenExternalUrl("https://example.test/replacement")))
        assertEquals("https://example.test/replacement", shadowOf(current).nextStartedActivity.data.toString())
        replacementController.pause().stop().destroy()
        assertTrue(owner.execute(Command.ShowShareSheet("private:destroyed")))
        assertEquals(listOf(Event.HardwareUnavailable("share_sheet")), events)
        assertNull(shadowOf(current).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun hostClosedDuringProviderLookupDoesNotLaunchOrAcquireAgain() {
        lateinit var host: ActivityExternalIntentHost
        var acquired = 0
        host =
            ActivityExternalIntentHost {
                acquired++
                host.close()
                activity
            }
        val events = mutableListOf<Event>()
        val owner = AndroidDisplayExecutor(application, externalIntentHost = host) { events.add(it) }.also(owners::add)
        assertTrue(owner.execute(Command.OpenExternalUrl("private:first")))
        assertTrue(owner.execute(Command.ShowShareSheet("private:second")))
        assertEquals(1, acquired)
        assertEquals(listOf(Event.HardwareUnavailable("external_url"), Event.HardwareUnavailable("share_sheet")), events)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun workerOwnerCloseFencesTheNativeHostBeforePostedCleanupRuns() {
        val host = ActivityExternalIntentHost { activity }
        val events = mutableListOf<Event>()
        val owner = AndroidDisplayExecutor(application, externalIntentHost = host) { events.add(it) }.also(owners::add)
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { owner.close() }.get(10, TimeUnit.SECONDS)
            assertFalse(owner.execute(Command.ShowShareSheet("private:closed")))
            val error = assertThrows(IllegalStateException::class.java) { host.open("private:direct") }
            assertEquals("External intent hosting unavailable", error.message)
            assertNull(error.cause)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(emptyList<Event>(), events)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun directHostWorkerCloseIsTerminalAndSafeOnRepeatedClose() {
        var acquired = 0
        val host =
            ActivityExternalIntentHost {
                acquired++
                activity
            }
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { host.close() }.get(10, TimeUnit.SECONDS)
            host.close()
            val error = assertThrows(IllegalStateException::class.java) { host.share("private:closed") }
            assertEquals("External intent hosting unavailable", error.message)
            assertNull(error.cause)
            assertEquals(0, acquired)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun refusedOrThrowingFailureAdmissionClosesTheOwner() {
        for (throws in listOf(false, true)) {
            val observed = mutableListOf<Event>()
            val owner =
                AndroidDisplayExecutor(application) {
                    observed.add(it)
                    if (throws) throw IllegalArgumentException("private callback detail")
                    false
                }.also(owners::add)
            assertTrue(owner.execute(Command.ShowShareSheet("private:token")))
            assertFalse(owner.execute(Command.OpenExternalUrl("private:after-close")))
            assertEquals(listOf(Event.HardwareUnavailable("share_sheet")), observed)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
