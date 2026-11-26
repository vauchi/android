// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.CameraPurpose
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.ToastSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: contact_exchange.feature @exchange
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidDisplayExecutorTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun preparedEffectsAndOpaqueInputsUseTheDisplayOwnerUntilClosure() {
        val events = mutableListOf<Event>()
        val toast = Command.ShowToast(ToastSpec("Core prepared copy"))
        val input = Event.ActionActivated("opaque-interaction", "opaque-surface")
        AndroidDisplayExecutor(application) { events.add(it) }.use { owner ->
            assertTrue(owner.execute(toast))
            assertEquals(listOf(DisplayEffect(1uL, toast)), owner.state.value.effects)
            owner.consumeEffect(99uL)
            assertEquals(listOf(DisplayEffect(1uL, toast)), owner.state.value.effects)
            owner.consumeEffect(1uL)
            assertEquals(emptyList<DisplayEffect>(), owner.state.value.effects)
            assertTrue(owner.report(input))
            owner.close()
            assertFalse(owner.report(input))
            assertFalse(owner.execute(toast))
            assertEquals(listOf(input), events)
            assertEquals(emptyList<DisplayEffect>(), owner.state.value.effects)
        }
    }

    @Test
    fun hardwareCommandsCannotAcquireCaptureOrChangeTheDisplayViewport() {
        val events = mutableListOf<Event>()
        AndroidDisplayExecutor(application) { events.add(it) }.use { owner ->
            assertTrue(owner.execute(Command.CameraViewport("opaque-preview")))
            val before = owner.state.value
            val hardware =
                listOf(
                    Command.CameraStop("opaque-preview"),
                    Command.CameraCapture("opaque-preview", CameraPurpose.Photo),
                    Command.SwitchCamera(true),
                    Command.BleStopScanning,
                    Command.LocationRequest(1u),
                )
            assertEquals(List(hardware.size) { false }, hardware.map(owner::execute))
            assertEquals(before, owner.state.value)
            assertEquals(emptyList<Event>(), events)
        }
    }

    @Test
    fun aThrowingAdmissionCallbackClosesTheDisplayOwner() {
        AndroidDisplayExecutor(application) { throw IllegalStateException("private host detail") }.use { owner ->
            assertFalse(owner.report(Event.ActionActivated("opaque", "surface")))
            assertFalse(owner.execute(Command.CameraViewport("not-acquired")))
            assertEquals(DisplaySnapshot(), owner.state.value)
        }
    }
}
