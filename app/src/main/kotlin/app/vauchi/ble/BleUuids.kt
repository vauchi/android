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
     * token (identity-derived, ADR-043) is advertised as a 16-bit
     * Bluetooth-base service UUID alongside the 128-bit [SERVICE] UUID — the
     * only channel iOS CoreBluetooth peripherals can advertise (service UUIDs
     * only, no service/manufacturer data), and 16-bit is the only compressed
     * width every Android stack transmits intact: pre-Android-9 advertisers
     * (Galaxy S7 / Android 8) truncate a 32-bit UUID to its low 16 bits
     * (`ff5b2478` went on air as `00002478`), which deadlocked the role
     * tiebreak with both peers as responder (P5b stall,
     * `2026-06-06-android-ble-execution`, 2026-06-10). Advert budget:
     * flags(3) + 128-bit(18) + 16-bit(4) = 25 <= 31. Core's full-vs-prefix
     * compare still resolves for identities with distinct 2-byte prefixes
     * (collision ~= 2^-16 per pair — a stall recovered by the exchange
     * timeout/cancel, tracked in `2026-06-04-exchange-terminal-screens`).
     */
    const val ADV_TOKEN_BYTES = 2

    /** Token width of the retired 32-bit format (P5c v1), still decoded. */
    private const val LEGACY_ADV_TOKEN_BYTES = 4

    // Low 64 bits of the Bluetooth base UUID
    // (00000000-0000-1000-8000-00805F9B34FB): a 16/32-bit UUID shares the base's
    // low 96 bits and encodes its value in the top 32 bits of the high half.
    private val BASE_LSB =
        UUID.fromString("00000000-0000-1000-8000-00805f9b34fb").leastSignificantBits

    /**
     * Encode the first [ADV_TOKEN_BYTES] of [token] as a 16-bit Bluetooth-base
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
     * Decode a 16-bit Bluetooth-base service UUID back to its [ADV_TOKEN_BYTES]
     * token bytes — or a legacy 32-bit one to its 4 bytes (an un-updated peer
     * still advertising the v1 format; decoding it whole keeps the
     * full-vs-prefix compare consistent). `null` if [uuid] is not a base UUID
     * (e.g. the 128-bit [SERVICE] UUID). The central picks the token UUID out
     * of a scan result's service-UUID list with this.
     */
    fun serviceUuidToToken(uuid: UUID): ByteArray? {
        if (uuid.leastSignificantBits != BASE_LSB) return null
        if ((uuid.mostSignificantBits and 0xFFFFFFFFL) != 0x1000L) return null
        val v = uuid.mostSignificantBits ushr 32
        val width = if (v <= 0xFFFFL) ADV_TOKEN_BYTES else LEGACY_ADV_TOKEN_BYTES
        return ByteArray(width) { i ->
            ((v ushr (8 * (width - 1 - i))) and 0xff).toByte()
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

    /**
     * Write characteristics that use Write-With-Response (vs no-response).
     * HANDSHAKE_NOTIFY is bidirectional: the responder notifies phase data,
     * then the initiator writes the final reciprocity acknowledgement.
     */
    val writeWithResponse: Set<String> =
        setOf(CARD_EXCHANGE, CHALLENGE, HANDSHAKE_WRITE, HANDSHAKE_NOTIFY)

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
