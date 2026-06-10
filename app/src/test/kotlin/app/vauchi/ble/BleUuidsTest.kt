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
 * Unit tests for the role-tiebreak token <-> 16-bit service UUID encoding
 * (P5c unified advertisement format, v2). Pure `java.util.UUID` math — runs on
 * the plain JVM, no Android runtime. This pins the bit-twiddling that the
 * device test (P5b) exercises end-to-end.
 *
 * 16-bit, not 32-bit: pre-Android-9 stacks (Galaxy S7 / Android 8) truncate a
 * 32-bit service UUID to its low 16 bits when advertising (`ff5b2478` went on
 * air as `00002478`), which deadlocked the role tiebreak with both peers as
 * responder. Only the 16-bit compressed form survives every stack intact.
 * See `2026-06-06-android-ble-execution` (P5b re-test, 2026-06-10).
 */
class BleUuidsTest {
    @Test
    fun token_round_trips_through_a_16bit_service_uuid() {
        val token = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())
        val uuid = BleUuids.tokenToServiceUuid(token)
        // Only the first ADV_TOKEN_BYTES are carried; they survive the round-trip.
        assertArrayEquals(
            token.copyOf(BleUuids.ADV_TOKEN_BYTES),
            BleUuids.serviceUuidToToken(uuid),
        )
    }

    @Test
    fun adv_token_is_two_bytes() {
        // The tiebreak prefix compare needs token[0..ADV_TOKEN_BYTES]; widening
        // it again would reintroduce the pre-Android-9 truncation deadlock.
        assertEquals(2, BleUuids.ADV_TOKEN_BYTES)
    }

    @Test
    fun encoded_uuid_is_a_16bit_bluetooth_base_uuid() {
        val uuid = BleUuids.tokenToServiceUuid(byteArrayOf(0x10, 0x42))
        assertEquals(
            UUID.fromString("00001042-0000-1000-8000-00805f9b34fb"),
            uuid,
        )
    }

    @Test
    fun legacy_32bit_uuid_decodes_to_its_four_byte_token() {
        // Transition compat: an un-updated peer (iOS pre-v2) still advertises
        // the 4-byte token as a 32-bit base UUID; decode it whole so the
        // full-vs-prefix compare stays consistent.
        val uuid = UUID.fromString("f910c8e8-0000-1000-8000-00805f9b34fb")
        assertArrayEquals(
            byteArrayOf(0xf9.toByte(), 0x10, 0xc8.toByte(), 0xe8.toByte()),
            BleUuids.serviceUuidToToken(uuid),
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
        val a = BleUuids.tokenToServiceUuid(byteArrayOf(1, 2))
        val b = BleUuids.tokenToServiceUuid(byteArrayOf(1, 3))
        assertNotEquals(a, b)
    }

    @Test
    fun short_token_round_trips_zero_padded() {
        val uuid = BleUuids.tokenToServiceUuid(byteArrayOf(0xAB.toByte()))
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0),
            BleUuids.serviceUuidToToken(uuid),
        )
    }
}
