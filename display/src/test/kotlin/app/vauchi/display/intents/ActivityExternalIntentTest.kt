// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.intents

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.AndroidDisplayExecutor
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Feature: generic_presentation_protocol.feature — native external intent hosting.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ActivityExternalIntentTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val main = shadowOf(Looper.getMainLooper())
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val events = mutableListOf<Event>()
    private val owners = mutableListOf<AndroidDisplayExecutor>()

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
        main.idle()
        controller.pause().stop().destroy()
        main.idle()
    }

    private fun owner(provider: () -> ComponentActivity? = { activity }): AndroidDisplayExecutor =
        AndroidDisplayExecutor(application, externalIntentHost = ActivityExternalIntentHost(provider)) { events.add(it) }
            .also(owners::add)

    @Test
    fun preparedUrlsAreViewDataWithoutParsedIntentFieldsOrUriGrants() {
        val owner = owner()
        val before = owner.state.value
        for (url in listOf(
            "https://example.test/秘密?q=Å#🔐",
            "mailto:alice@example.test",
            "tel:+41790000000",
            "intent:#Intent;component=other/.Private;S.secret=x;end",
        )) {
            assertTrue(owner.execute(Command.OpenExternalUrl(url)))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.data.toString())
            assertEquals(0, intent.flags)
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertNull(intent.extras)
            assertNull(intent.clipData)
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(before, owner.state.value)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun generatedPreparedPayloadsRemainDataThroughBothNativeLaunches() {
        val owner = owner()
        runBlocking {
            checkAll(
                100,
                Arb.string(0..128),
                Arb.element("", "é🔐", "\u0000", "\"\\\n", "#Intent;component=other/.Private;end"),
            ) { generated, edge ->
                val url = "https://example.test/$generated$edge"
                assertTrue(owner.execute(Command.OpenExternalUrl(url)))
                val view = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals(url, view.data.toString())
                assertNull(view.component)
                assertNull(view.extras)
                assertTrue(owner.execute(Command.ShowShareSheet(url)))
                val chooser = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
                assertNull(send.component)
                assertNull(send.clipData!!.getItemAt(0).uri)
                assertNull(send.clipData!!.getItemAt(0).intent)
            }
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(emptyList<Event>(), events)
        assertEquals(emptyList<Any>(), owner.state.value.effects)
    }

    @Test
    fun shareLaunchesATextChooserWithExactPayloadWithoutClaimingDelivery() {
        val owner = owner()
        val url = "https://example.test/秘密?token=opaque#🔐"
        assertTrue(owner.execute(Command.ShowShareSheet(url)))
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, chooser.flags)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet())
        assertEquals(0, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv())
        assertNull(send.component)
        assertNull(send.data)
        val clip = send.clipData!!
        assertEquals(1, clip.itemCount)
        assertEquals(url, clip.getItemAt(0).text.toString())
        assertNull(clip.getItemAt(0).uri)
        assertNull(clip.getItemAt(0).intent)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun missingAndFinishingActivitiesReportSafeFailuresWithoutLaunching() {
        val owner = owner { null }
        assertTrue(owner.execute(Command.OpenExternalUrl("private:payload")))
        activity.finish()
        val finishing = owner()
        assertTrue(finishing.execute(Command.ShowShareSheet("private:token")))
        assertEquals(listOf(Event.HardwareUnavailable("external_url"), Event.HardwareUnavailable("share_sheet")), events)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun nativeLaunchFailuresBecomeSafeEventsAndDoNotPreventLaterCommands() {
        val failureController = Robolectric.buildActivity(IntentFailureActivity::class.java).setup()
        try {
            val failure = failureController.get()
            val owner = owner { failure }
            assertTrue(owner.execute(Command.OpenExternalUrl("private:payload")))
            assertTrue(owner.execute(Command.ShowShareSheet("private:token")))
            assertEquals(listOf(Event.HardwareUnavailable("external_url"), Event.HardwareUnavailable("share_sheet")), events)
            failure.reject = false
            assertTrue(owner.execute(Command.OpenExternalUrl("https://example.test/recovered")))
            assertEquals("https://example.test/recovered", shadowOf(failure).nextStartedActivity.data.toString())
            assertEquals(2, events.size)
        } finally {
            failureController.pause().stop().destroy()
        }
    }

    @Test
    fun aThrowingProviderDoesNotExposeItsPrivateMessage() {
        val owner = owner { throw SecurityException("secret:path?token=value") }
        assertTrue(owner.execute(Command.OpenExternalUrl("private:payload")))
        assertEquals(listOf(Event.HardwareUnavailable("external_url")), events)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun providerClosingItsOwnerCannotLaunchAfterReturning() {
        lateinit var owner: AndroidDisplayExecutor
        owner =
            owner {
                owner.close()
                activity
            }
        assertTrue(owner.execute(Command.OpenExternalUrl("private:payload")))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(emptyList<Event>(), events)
        assertFalse(owner.execute(Command.ShowShareSheet("private:token")))
    }

    @Test
    fun workerMisuseNeverAcquiresActivityAndMainCanRetry() {
        var acquisitions = 0
        val owner =
            owner {
                acquisitions++
                activity
            }
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(worker.submit<Boolean> { owner.execute(Command.OpenExternalUrl("private:worker")) }.get(10, TimeUnit.SECONDS))
            assertEquals(0, acquisitions)
            assertEquals(listOf(Event.HardwareUnavailable("external_url")), events)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(owner.execute(Command.OpenExternalUrl("https://example.test/main")))
            assertEquals(1, acquisitions)
            assertEquals("https://example.test/main", shadowOf(activity).nextStartedActivity.data.toString())
        } finally {
            worker.shutdownNow()
        }
    }
}

class IntentFailureActivity : ComponentActivity() {
    var reject = true

    override fun startActivity(
        intent: Intent,
        options: Bundle?,
    ) {
        if (reject) throw ActivityNotFoundException("private native diagnostic")
        super.startActivity(intent, options)
    }
}
