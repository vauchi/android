// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.ExportFileSpec
import app.vauchi.display.AndroidDisplayExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — prepared asynchronous native file export.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidFileExportTest {
    @Test
    fun asyncExportReportsMissingHostAndOtherCommandsKeepTheirNativeRoute() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val events = mutableListOf<Event>()
        val owner = AndroidDisplayExecutor(application) { events.add(it) }
        val export = Command.ExportFile(ExportFileSpec(listOf(1u.toUByte()), "application/octet-stream", "backup.vauchi"))
        try {
            runBlocking {
                assertFalse(owner.execute(export))
                assertEquals(emptyList<Event>(), events)
                assertTrue(owner.executeAsync(export))
                assertTrue(owner.executeAsync(Command.SetIdleTimerDisabled(true)))
                assertFalse(owner.executeAsync(Command.BleStopScanning))
                assertEquals(listOf(Event.HardwareUnavailable("file_export"), Event.HardwareUnavailable("idle_timer")), events)
                owner.close()
                assertFalse(owner.executeAsync(export))
                assertEquals(2, events.size)
            }
        } finally {
            owner.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
