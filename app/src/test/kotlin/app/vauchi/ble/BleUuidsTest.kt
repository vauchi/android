// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for the role-tiebreak token <-> 32-bit service UUID encoding
 * (P5c unified advertisement format). Pure `java.util.UUID` math — runs on the
 * plain JVM, no Android runtime. This pins the bit-twiddling that the device
 * test (P5b) exercises end-to-end.
 */
class BleUuidsTest {
    @Test
    fun token_round_trips_through_a_32bit_service_uuid() {
        val token = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())
        val uuid = BleUuids.tokenToServiceUuid(token)
        // Only the first ADV_TOKEN_BYTES are carried; they survive the round-trip.
        assertArrayEquals(
            token.copyOf(BleUuids.ADV_TOKEN_BYTES),
            BleUuids.serviceUuidToToken(uuid),
        )
    }

    @Test
    fun encoded_uuid_is_a_32bit_bluetooth_base_uuid() {
        val uuid = BleUuids.tokenToServiceUuid(byteArrayOf(0x00, 0x00, 0x10, 0x42))
        assertEquals(
            UUID.fromString("00001042-0000-1000-8000-00805f9b34fb"),
            uuid,
        )
    }

    @Test
    fun the_128bit_service_uuid_is_not_mistaken_for_a_token() {
        // The fixed 128-bit service UUID must decode to null so the central
        // picks the token UUID (the other one) out of the scan-result list.
        assertNull(BleUuids.serviceUuidToToken(BleUuids.uuid(BleUuids.SERVICE)))
    }

    @Test
    fun distinct_tokens_yield_distinct_uuids_for_the_tiebreak() {
        val a = BleUuids.tokenToServiceUuid(byteArrayOf(1, 2, 3, 4))
        val b = BleUuids.tokenToServiceUuid(byteArrayOf(1, 2, 3, 5))
        assertNotEquals(a, b)
    }

    @Test
    fun short_token_round_trips_zero_padded() {
        val uuid = BleUuids.tokenToServiceUuid(byteArrayOf(0xAB.toByte()))
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0, 0, 0),
            BleUuids.serviceUuidToToken(uuid),
        )
    }
}
