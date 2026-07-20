// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePermissionActionGateTest {
    private val glance =
        UserAction.ListItemSelected(
            componentId = "exchange_modes",
            itemId = "mode:glance",
        )

    @Test
    fun mode_action_waits_until_permissions_are_granted() {
        val gate =
            ModePermissionActionGate { listOf("camera", "bluetooth") }

        assertEquals(listOf("camera", "bluetooth"), gate.defer(glance))
        assertEquals(glance, gate.resolve(allGranted = true))
        assertNull(gate.resolve(allGranted = true))
    }

    @Test
    fun denied_permissions_discard_the_pending_mode_action() {
        val gate = ModePermissionActionGate { listOf("camera") }

        assertTrue(gate.defer(glance).isNotEmpty())
        assertNull(gate.resolve(allGranted = false))
        assertNull(gate.resolve(allGranted = true))
    }
}
