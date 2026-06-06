// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import java.util.UUID

/**
 * GATT service + characteristic UUIDs for the vauchi BLE exchange.
 *
 * Mirrors the constants in `core/vauchi-core/src/exchange/ble.rs`. The
 * peripheral's GATT server exposes all of these; the central writes to the
 * WRITE characteristics and subscribes to the NOTIFY ones. Core addresses them
 * by UUID in `Command::BleWriteCharacteristic{uuid}` /
 * `BleReadCharacteristic{uuid}`, so the bridge stays generic.
 */
object BleUuids {
    const val SERVICE = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    /** Read + Notify — exchange payload. */
    const val EXCHANGE_PAYLOAD = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"

    /** Write + Notify — card exchange (legacy). */
    const val CARD_EXCHANGE = "a1b2c3d4-e5f6-7890-abcd-ef1234567892"

    /** Write + Notify — challenge-response (legacy). */
    const val CHALLENGE = "a1b2c3d4-e5f6-7890-abcd-ef1234567893"

    /** Write (with response) — initiator → responder handshake. */
    const val HANDSHAKE_WRITE = "a1b2c3d4-e5f6-7890-abcd-ef1234567894"

    /** Notify — responder → initiator handshake. */
    const val HANDSHAKE_NOTIFY = "a1b2c3d4-e5f6-7890-abcd-ef1234567895"

    /** Write (no response) — initiator → responder data chunks. */
    const val DATA_WRITE = "a1b2c3d4-e5f6-7890-abcd-ef1234567896"

    /** Notify — responder → initiator data chunks. */
    const val DATA_NOTIFY = "a1b2c3d4-e5f6-7890-abcd-ef1234567897"

    /** Client Characteristic Configuration descriptor (enables notifications). */
    const val CCC_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"

    /** All exchange characteristics the peripheral's GATT server exposes. */
    val allCharacteristics: List<String> =
        listOf(
            EXCHANGE_PAYLOAD,
            CARD_EXCHANGE,
            CHALLENGE,
            HANDSHAKE_WRITE,
            HANDSHAKE_NOTIFY,
            DATA_WRITE,
            DATA_NOTIFY,
        )

    /** Characteristics that support notifications (NOTIFY property). */
    val notifyCharacteristics: Set<String> =
        setOf(EXCHANGE_PAYLOAD, CARD_EXCHANGE, CHALLENGE, HANDSHAKE_NOTIFY, DATA_NOTIFY)

    /** Write characteristics that use Write-With-Response (vs no-response). */
    val writeWithResponse: Set<String> =
        setOf(CARD_EXCHANGE, CHALLENGE, HANDSHAKE_WRITE)

    fun uuid(s: String): UUID = UUID.fromString(s)
}
