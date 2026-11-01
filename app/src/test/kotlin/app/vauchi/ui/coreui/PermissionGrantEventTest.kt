// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android raises the Bluetooth and camera prompts itself, so Core never
 * learned of a grant and Glance's 15 s search ran down while the prompt was
 * open (vauchi/private#1022). The shell now reports the grant as Core's
 * canonical `Event::PermissionGranted`; the generated `MobileEvent` has no
 * such case, so it goes as event JSON.
 */
class PermissionGrantEventTest {
    @Test
    fun `the grant is Core's canonical PermissionGranted event`() {
        assertEquals(
            """{"PermissionGranted":{"transport":"ble"}}""",
            PermissionGrantEvent.json("ble"),
        )
        assertEquals(
            """{"PermissionGranted":{"transport":"camera"}}""",
            PermissionGrantEvent.json("camera"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a transport that is not a plain token is refused`() {
        PermissionGrantEvent.json("ble\",\"x\":\"y")
    }

    @Test
    fun `Bluetooth reports a grant only when every requested permission was granted`() {
        assertTrue(PermissionGrantEvent.bleGranted(mapOf("BLUETOOTH_SCAN" to true, "BLUETOOTH_CONNECT" to true)))
        assertFalse(PermissionGrantEvent.bleGranted(mapOf("BLUETOOTH_SCAN" to true, "BLUETOOTH_CONNECT" to false)))
        assertFalse(PermissionGrantEvent.bleGranted(emptyMap()))
    }
}
