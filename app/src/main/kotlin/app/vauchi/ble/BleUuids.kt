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

    /**
     * Number of token bytes carried in the advertisement. Core's tiebreak
     * token (identity-derived, ADR-043) is advertised as a 32-bit
     * Bluetooth-base service UUID alongside the 128-bit [SERVICE] UUID — the
     * only portable way to convey it, since iOS CoreBluetooth peripherals
     * cannot advertise service/manufacturer data (only service UUIDs). Two
     * 128-bit UUIDs overflow the 31-byte advert, so the token is the first 4
     * bytes (a 32-bit UUID): flags(3) + 128-bit(18) + 32-bit(6) = 27 <= 31.
     * Core's full-vs-prefix compare still resolves for distinct identities
     * (collision ~= 2^-32, a recoverable stall). See
     * `2026-06-07-ios-ble-execution-parity-plan`.
     */
    const val ADV_TOKEN_BYTES = 4

    // Low 64 bits of the Bluetooth base UUID
    // (00000000-0000-1000-8000-00805F9B34FB): a 16/32-bit UUID shares the base's
    // low 96 bits and encodes its value in the top 32 bits of the high half.
    private val BASE_LSB =
        UUID.fromString("00000000-0000-1000-8000-00805f9b34fb").leastSignificantBits

    /**
     * Encode the first [ADV_TOKEN_BYTES] of [token] as a 32-bit Bluetooth-base
     * service UUID for advertising; the peer reads it back via
     * [serviceUuidToToken].
     */
    fun tokenToServiceUuid(token: ByteArray): UUID {
        var v = 0L
        for (i in 0 until ADV_TOKEN_BYTES) {
            v = (v shl 8) or (token.getOrElse(i) { 0 }.toLong() and 0xff)
        }
        return UUID((v shl 32) or 0x1000L, BASE_LSB)
    }

    /**
     * Decode a 32-bit Bluetooth-base service UUID back to its [ADV_TOKEN_BYTES]
     * token bytes, or `null` if [uuid] is not a 32-bit base UUID (e.g. the
     * 128-bit [SERVICE] UUID). The central picks the token UUID out of a scan
     * result's service-UUID list with this.
     */
    fun serviceUuidToToken(uuid: UUID): ByteArray? {
        if (uuid.leastSignificantBits != BASE_LSB) return null
        if ((uuid.mostSignificantBits and 0xFFFFFFFFL) != 0x1000L) return null
        val v = uuid.mostSignificantBits ushr 32
        return ByteArray(ADV_TOKEN_BYTES) { i ->
            ((v ushr (8 * (ADV_TOKEN_BYTES - 1 - i))) and 0xff).toByte()
        }
    }

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

    /**
     * Routing for `Command::BleWriteCharacteristic` — the UUID encodes the
     * direction (handshake machine: initiator writes …894/…896, responder
     * notifies …895/…897). A notify-char write means "the responder
     * (peripheral) pushes to the central"; anything else is the initiator
     * (central) doing a GATT write.
     */
    val peripheralNotifyChars: Set<String> = setOf(HANDSHAKE_NOTIFY, DATA_NOTIFY)

    fun uuid(s: String): UUID = UUID.fromString(s)
}
