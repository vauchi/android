// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExchangeModePermissions]. The permission strings and
 * `Build.VERSION_CODES.S` are compile-time constants, so these run on the plain
 * JVM without an Android runtime; `sdkInt` is injected to exercise the
 * version-gated Bluetooth mapping deterministically.
 */
class ExchangeModePermissionsTest {
    private val api31 = Build.VERSION_CODES.S // Android 12
    private val api26 = Build.VERSION_CODES.O // Android 8

    @Test
    fun hover_requires_camera_and_microphone() {
        val perms = ExchangeModePermissions.forMode("mode:hover", sdkInt = api31)
        assertTrue("hover needs CAMERA", perms.contains(Manifest.permission.CAMERA))
        assertTrue("hover needs RECORD_AUDIO", perms.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun glance_requires_camera_and_bluetooth_on_api31() {
        val perms = ExchangeModePermissions.forMode("glance", sdkInt = api31)
        assertTrue(perms.contains(Manifest.permission.CAMERA))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
    }

    @Test
    fun magic_requires_bluetooth_and_microphone_on_api31() {
        val perms = ExchangeModePermissions.forMode("mode:magic", sdkInt = api31)
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse("magic does not use the camera", perms.contains(Manifest.permission.CAMERA))
    }

    @Test
    fun ble_mode_uses_fine_location_before_api31() {
        // Pre-Android 12 (e.g. Samsung S7): BLE scanning needs location, not the
        // runtime BLUETOOTH_* permissions.
        val perms = ExchangeModePermissions.forMode("bump", sdkInt = api26)
        assertTrue(perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(perms.contains(Manifest.permission.BLUETOOTH_SCAN))
    }

    @Test
    fun shake_requires_bluetooth_without_microphone() {
        val perms = ExchangeModePermissions.forMode("shake", sdkInt = api31)
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse("shake has no audio stage", perms.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun nfc_relay_and_usb_modes_need_no_runtime_permission() {
        // NFC is install-time; relay and USB need nothing.
        assertEquals(emptyList<String>(), ExchangeModePermissions.forMode("tap_tap", sdkInt = api31))
        assertEquals(emptyList<String>(), ExchangeModePermissions.forMode("link", sdkInt = api31))
        assertEquals(emptyList<String>(), ExchangeModePermissions.forMode("cable", sdkInt = api31))
    }

    @Test
    fun unknown_mode_needs_no_permission() {
        assertEquals(emptyList<String>(), ExchangeModePermissions.forMode("mode:does_not_exist"))
    }

    @Test
    fun item_id_prefix_is_optional() {
        assertEquals(
            ExchangeModePermissions.forMode("hover", sdkInt = api31),
            ExchangeModePermissions.forMode("mode:hover", sdkInt = api31),
        )
    }

    @Test
    fun permissions_are_deduplicated() {
        val perms = ExchangeModePermissions.forMode("tap_hover_shake", sdkInt = api31)
        assertEquals("no duplicate permission entries", perms.distinct().size, perms.size)
    }
}
