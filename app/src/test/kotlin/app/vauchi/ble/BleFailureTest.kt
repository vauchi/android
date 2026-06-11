// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent

/**
 * Unit tests for [BleFailure]'s pure error-to-event mapping. The radio/OS
 * flow itself is OS-tested (CC-23); these pin the classification the
 * exchange engine relies on to fail BLE flows visibly instead of waiting
 * forever (`2026-06-11-exchange-waits-forever-without-capabilities`).
 */
class BleFailureTest {
    @Test
    fun scan_permission_failure_maps_to_permission_denied() {
        assertEquals(
            MobileEvent.PermissionDenied("ble"),
            BleFailure.toEvent("Missing BLUETOOTH_SCAN permission"),
        )
    }

    @Test
    fun connect_permission_failure_maps_to_permission_denied() {
        assertEquals(
            MobileEvent.PermissionDenied("ble"),
            BleFailure.toEvent("Missing BLUETOOTH_CONNECT permission"),
        )
    }

    @Test
    fun gatt_server_permission_failure_maps_to_permission_denied() {
        assertEquals(
            MobileEvent.PermissionDenied("ble"),
            BleFailure.toEvent("BLE permission not granted"),
        )
    }

    @Test
    fun adapter_off_maps_to_hardware_unavailable() {
        assertEquals(
            MobileEvent.HardwareUnavailable("ble"),
            BleFailure.toEvent("BLE adapter off"),
        )
    }

    @Test
    fun scanner_unavailable_maps_to_hardware_unavailable() {
        assertEquals(
            MobileEvent.HardwareUnavailable("ble"),
            BleFailure.toEvent("BLE scanner unavailable (adapter off?)"),
        )
    }

    @Test
    fun empty_error_maps_to_hardware_unavailable() {
        assertEquals(
            MobileEvent.HardwareUnavailable("ble"),
            BleFailure.toEvent(""),
        )
    }
}
