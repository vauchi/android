// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import uniffi.vauchi_mobile.MobileNfcHandshake

/**
 * Android Host Card Emulation service for NFC contact exchange.
 *
 * Acts as the responder (card side) in the three-phase NFC handshake.
 * The reader (iOS CoreNFC or Android NfcAdapter) drives the protocol.
 *
 * Protocol flow from HCE perspective:
 * 1. Reader sends SELECT AID → we return SW_OK
 * 2. Reader sends key offer (INS=E0) → we return key ack + encrypted card
 * 3. Reader sends encrypted card (INS=E2) → we decrypt and return SW_OK
 */
class VauchiHceService : HostApduService() {
    companion object {
        private const val TAG = "VauchiHce"

        /** Vauchi NFC exchange AID: F0564155434849 */
        val VAUCHI_AID =
            byteArrayOf(
                0xF0.toByte(),
                0x56,
                0x41,
                0x55,
                0x43,
                0x48,
                0x49,
            )

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        private val SW_WRONG_DATA = byteArrayOf(0x6A.toByte(), 0x80.toByte())

        const val INS_KEY_OFFER: Byte = 0xE0.toByte()
        const val INS_GET_ENCRYPTED_CARD: Byte = 0xE1.toByte()
        const val INS_ENCRYPTED_CARD: Byte = 0xE2.toByte()

        /**
         * The active handshake session. Set by the UI before exchange starts.
         */
        @Volatile
        var activeSession: MobileNfcHandshake? = null
    }

    /** Encrypted card bytes from Phase 2, held until reader fetches with INS_GET_ENCRYPTED_CARD */
    private var pendingEncryptedCard: ByteArray? = null

    override fun processCommandApdu(
        commandApdu: ByteArray,
        extras: Bundle?,
    ): ByteArray {
        if (commandApdu.size < 4) return SW_WRONG_DATA

        if (isSelectAid(commandApdu)) {
            Log.d(TAG, "AID selected")
            return SW_OK
        }

        val session =
            activeSession ?: run {
                Log.w(TAG, "No active session")
                return SW_CONDITIONS_NOT_SATISFIED
            }

        return try {
            val ins = commandApdu[1]
            Log.d(TAG, "APDU received: INS=%02x, size=${commandApdu.size}".format(ins))
            val data = extractData(commandApdu)
            Log.d(TAG, "Extracted data: size=${data.size}")
            when (ins) {
                INS_KEY_OFFER -> {
                    handleKeyOffer(session, data)
                }

                INS_GET_ENCRYPTED_CARD -> {
                    handleGetEncryptedCard()
                }

                INS_ENCRYPTED_CARD -> {
                    handleEncryptedCard(session, data)
                }

                else -> {
                    Log.w(TAG, "Unknown INS: %02x".format(ins))
                    SW_WRONG_DATA
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "APDU error: ${e.message}", e)
            SW_CONDITIONS_NOT_SATISFIED
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: ${if (reason == DEACTIVATION_LINK_LOSS) "link_loss" else "deselected"}")
    }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < 6) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return false
        if (apdu[2] != 0x04.toByte() || apdu[3] != 0x00.toByte()) return false
        val aidLen = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + aidLen) return false
        return apdu.sliceArray(5 until 5 + aidLen).contentEquals(VAUCHI_AID)
    }

    private fun extractData(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return ByteArray(0)
        val lc = apdu[4].toInt() and 0xFF
        if (lc != 0) {
            // Short APDU: Lc is 1 byte
            if (apdu.size < 5 + lc) return ByteArray(0)
            return apdu.sliceArray(5 until 5 + lc)
        } else if (apdu.size >= 7) {
            // Extended APDU: Lc byte is 0x00, followed by 2-byte length
            val extLen = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            if (apdu.size < 7 + extLen) return ByteArray(0)
            return apdu.sliceArray(7 until 7 + extLen)
        }
        return ByteArray(0)
    }

    /**
     * Phase 2a (Responder): Process key offer from reader.
     * Returns key ack bytes + SW_OK. Encrypted card is stored for retrieval via INS_GET_ENCRYPTED_CARD.
     * Split into two APDUs to stay under HCE response size limits (~261 bytes).
     */
    private fun handleKeyOffer(
        session: MobileNfcHandshake,
        data: ByteArray,
    ): ByteArray {
        Log.d(TAG, "Processing key offer, data size=${data.size}")
        val ackResult = session.processKeyOffer(data)
        Log.d(TAG, "Key offer processed, ack size=${ackResult.keyAckBytes.size}, card size=${ackResult.encryptedCardBytes.size}")

        // Store encrypted card for next APDU
        pendingEncryptedCard = ackResult.encryptedCardBytes

        // Return only key ack + SW_OK
        val ackBytes = ackResult.keyAckBytes
        val response = ByteArray(ackBytes.size + 2)
        System.arraycopy(ackBytes, 0, response, 0, ackBytes.size)
        response[response.size - 2] = 0x90.toByte()
        response[response.size - 1] = 0x00
        Log.d(TAG, "Returning key ack response, size=${response.size}")
        return response
    }

    /**
     * Phase 2b (Responder): Return the encrypted card stored from Phase 2a.
     */
    private fun handleGetEncryptedCard(): ByteArray {
        val card =
            pendingEncryptedCard ?: run {
                Log.w(TAG, "No pending encrypted card")
                return SW_CONDITIONS_NOT_SATISFIED
            }
        pendingEncryptedCard = null
        val response = ByteArray(card.size + 2)
        System.arraycopy(card, 0, response, 0, card.size)
        response[response.size - 2] = 0x90.toByte()
        response[response.size - 1] = 0x00
        Log.d(TAG, "Returning encrypted card, size=${response.size}")
        return response
    }

    /**
     * Phase 3 (Responder): Process encrypted card from reader.
     * Completes the exchange on our side.
     */
    private fun handleEncryptedCard(
        session: MobileNfcHandshake,
        data: ByteArray,
    ): ByteArray {
        val result = session.processEncryptedCard(data)
        Log.d(TAG, "Exchange complete: ${result.remoteDisplayName}")
        return SW_OK
    }
}
