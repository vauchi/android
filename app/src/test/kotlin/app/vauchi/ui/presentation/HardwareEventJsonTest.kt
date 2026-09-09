// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileBleLinkDirection
import uniffi.vauchi_platform.MobileEvent

/**
 * Pins the canonical event JSON the shell hands to `dispatchJson` for every
 * hardware observation (ADR-066: one Event input). The shapes mirror Core's
 * serde spelling — externally tagged variant, snake_case fields, bytes as
 * unsigned integer arrays — which `event_from_json` decodes.
 */
class HardwareEventJsonTest {
    @Test
    fun byte_payloads_encode_as_unsigned_integer_arrays() {
        assertEquals(
            """{"NfcDataReceived":{"data":[0,255,16]}}""",
            MobileEvent.NfcDataReceived(byteArrayOf(0, -1, 16)).toEventJson(),
        )
    }

    @Test
    fun unit_variants_encode_as_bare_strings() {
        assertEquals(""""FilePickCancelledByUser"""", MobileEvent.FilePickCancelledByUser.toEventJson())
        assertEquals(""""ImagePickCancelled"""", MobileEvent.ImagePickCancelled.toEventJson())
    }

    @Test
    fun ble_link_direction_encodes_as_core_variant_name() {
        assertEquals(
            """{"BleConnected":{"device_id":"dev-1","direction":"Inbound"}}""",
            MobileEvent.BleConnected("dev-1", MobileBleLinkDirection.INBOUND).toEventJson(),
        )
        assertEquals(
            """{"BleDeviceDiscovered":{"id":"dev-2","rssi":-70,"adv_data":[1,2]}}""",
            MobileEvent.BleDeviceDiscovered("dev-2", -70, byteArrayOf(1, 2)).toEventJson(),
        )
    }

    @Test
    fun absent_optionals_encode_as_null() {
        assertEquals(
            """{"LocalNetworkAddressChanged":{"address":null}}""",
            MobileEvent.LocalNetworkAddressChanged(null).toEventJson(),
        )
        assertEquals(
            """{"LocationResult":{"latitude":47.5,"longitude":8.25,"accuracy_meters":null}}""",
            MobileEvent.LocationResult(47.5, 8.25, null).toEventJson(),
        )
    }

    @Test
    fun strings_are_json_escaped() {
        assertEquals(
            """{"HardwareError":{"transport":"ble","error":"GATT \"133\"\nretry"}}""",
            MobileEvent.HardwareError("ble", "GATT \"133\"\nretry").toEventJson(),
        )
    }

    @Test
    fun audio_samples_encode_as_float_array_with_rate() {
        assertEquals(
            """{"AudioSamplesRecorded":{"samples":[0.5,-1.0],"sample_rate":44100}}""",
            MobileEvent.AudioSamplesRecorded(listOf(0.5f, -1.0f), 44100u).toEventJson(),
        )
    }

    @Test
    fun accelerometer_sample_encodes_unsigned_timestamp() {
        assertEquals(
            """{"AccelerometerData":{"timestamp_ms":1,"x_milli_g":2,"y_milli_g":-3,"z_milli_g":4}}""",
            MobileEvent.AccelerometerData(1uL, 2, -3, 4).toEventJson(),
        )
    }

    @Test
    fun file_pick_carries_bytes_then_filename() {
        assertEquals(
            """{"FilePickedFromUser":{"bytes":[7],"filename":"backup.vauchi"}}""",
            MobileEvent.FilePickedFromUser(byteArrayOf(7), "backup.vauchi").toEventJson(),
        )
    }
}
