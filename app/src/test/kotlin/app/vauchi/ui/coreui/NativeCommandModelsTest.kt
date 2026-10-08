// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun orientation_restore_decodes_as_null() {
        val command =
            json.decodeFromString<CommandDTO>(
                """{"SetOrientationLock":{"orientation":null}}""",
            )

        assertTrue(command is CommandDTO.SetOrientationLock)
        assertNull((command as CommandDTO.SetOrientationLock).orientation)
    }

    @Test
    fun unknown_native_command_fails_visible() {
        val command = json.decodeFromString<CommandDTO>("""{"FutureEffect":{}}""")

        assertEquals(CommandDTO.Unknown("FutureEffect"), command)
    }

    /**
     * Core computes the wait (earliest, never past the deadline) and sends it
     * as `delay_millis`; Android used to derive it and ignored the deadline
     * (vauchi/private#548).
     */
    @Test
    fun schedule_wakeup_waits_cores_delay_never_past_the_deadline() {
        val command =
            json.decodeFromString<CommandDTO>(
                """{"ScheduleWakeup":{"earliest_secs":5,"deadline_secs":2,""" +
                    """"min_interval_secs":1,"earliest_millis":null,"delay_millis":2000}}""",
            )

        assertEquals(2000L, (command as CommandDTO.ScheduleWakeup).waitMillis)
    }

    @Test
    fun request_biometric_unlock_decodes_from_unit_variant() {
        val command = json.decodeFromString<CommandDTO>(""""RequestBiometricUnlock"""")

        assertEquals(CommandDTO.RequestBiometricUnlock, command)
    }
}
