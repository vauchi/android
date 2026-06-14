// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the T0.3 fire rule (the heart of `rememberPermissionState`'s `onDenied`
 * hook): report a denial only on a negative result, NEVER on a grant. The OS
 * prompt itself is OS-tested (CC-23); this tests the pure decision.
 */
class PermissionRationaleTest {
    @Test
    fun denied_result_is_reported_as_denial() {
        assertTrue(permissionResultIsDenial(granted = false))
    }

    @Test
    fun granted_result_is_not_reported_as_denial() {
        assertFalse(permissionResultIsDenial(granted = true))
    }
}
