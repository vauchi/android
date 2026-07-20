// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePermissionActionGateTest {
    @Test
    fun mode_action_waits_until_permissions_are_granted() {
        val gate =
            ModePermissionActionGate { listOf("camera", "bluetooth") }
        var dispatchCount = 0

        assertEquals(
            listOf("camera", "bluetooth"),
            gate.defer("mode:glance") { dispatchCount += 1 },
        )
        assertEquals(0, dispatchCount)
        gate.resolve(allGranted = true)
        assertEquals(1, dispatchCount)
        gate.resolve(allGranted = true)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun denied_permissions_discard_the_pending_mode_action() {
        val gate = ModePermissionActionGate { listOf("camera") }
        var dispatchCount = 0

        assertTrue(
            gate.defer("mode:glance") { dispatchCount += 1 }.isNotEmpty(),
        )
        gate.resolve(allGranted = false)
        gate.resolve(allGranted = true)
        assertEquals(0, dispatchCount)
    }
}
