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
}
