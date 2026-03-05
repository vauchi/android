// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import uniffi.vauchi_mobile.MobileNfcExchangeResult
import uniffi.vauchi_mobile.MobileNfcHandshake

/**
 * NFC reader (initiator) for the three-phase encrypted exchange.
 *
 * Connects to a remote device running VauchiHceService via IsoDep,
 * drives the handshake protocol, and returns the exchange result.
 */
class NfcReaderService {
    companion object {
        private const val TAG = "NfcReader"

        /** Maximum transceive timeout (ms) */
        private const val TRANSCEIVE_TIMEOUT_MS = 5000
    }

    /**
     * Perform the full NFC exchange with a discovered tag.
     *
     * @param tag The NFC tag from onTagDiscovered
     * @param session The initiator handshake session
     * @return Exchange result with remote contact data, or null on failure
     */
    fun performExchange(
        tag: Tag,
        session: MobileNfcHandshake,
    ): NfcExchangeOutcome {
        val isoDep = IsoDep.get(tag) ?: return NfcExchangeOutcome.Error("Tag does not support IsoDep")

        try {
            isoDep.connect()
            isoDep.timeout = TRANSCEIVE_TIMEOUT_MS

            // Step 1: SELECT AID
            val selectApdu = buildSelectAid(VauchiHceService.VAUCHI_AID)
            val selectResponse = isoDep.transceive(selectApdu)
            if (!isSuccess(selectResponse)) {
                return NfcExchangeOutcome.Error("AID selection failed")
            }

            // Step 2: Send key offer (Phase 1)
            val keyOffer = session.createKeyOffer()
            val offerApdu = buildApdu(VauchiHceService.INS_KEY_OFFER, keyOffer)
            val phase2Response = isoDep.transceive(offerApdu)
            if (!isSuccess(phase2Response)) {
                return NfcExchangeOutcome.Error("Key offer rejected")
            }

            // Parse response: [ack_len_hi, ack_len_lo, ack_bytes..., card_bytes...]
            val responseData = phase2Response.sliceArray(0 until phase2Response.size - 2)
            if (responseData.size < 2) {
                return NfcExchangeOutcome.Error("Response too short")
            }
            val ackLen = ((responseData[0].toInt() and 0xFF) shl 8) or (responseData[1].toInt() and 0xFF)
            if (responseData.size < 2 + ackLen) {
                return NfcExchangeOutcome.Error("Invalid ack length")
            }
            val ackBytes = responseData.sliceArray(2 until 2 + ackLen)
            val encryptedCard = responseData.sliceArray(2 + ackLen until responseData.size)

            // Step 3: Process key ack + encrypted card (Phase 2 initiator side)
            val ourEncryptedCard =
                session.processKeyAck(
                    ackBytes,
                    encryptedCard,
                )

            // Step 4: Send our encrypted card (Phase 3)
            val cardApdu = buildApdu(VauchiHceService.INS_ENCRYPTED_CARD, ourEncryptedCard)
            val phase3Response = isoDep.transceive(cardApdu)
            if (!isSuccess(phase3Response)) {
                return NfcExchangeOutcome.Error("Encrypted card rejected")
            }

            // Step 5: Confirm send success
            val result = session.confirmSendSuccess()
            Log.d(TAG, "Exchange complete: ${result.remoteDisplayName}")
            return NfcExchangeOutcome.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "NFC exchange failed: ${e.message}")
            // Try relay fallback if we have a shared key
            return try {
                val exchangeId = session.enterRelayFallback()
                NfcExchangeOutcome.RelayFallback(exchangeId)
            } catch (_: Exception) {
                NfcExchangeOutcome.Error(e.message ?: "Unknown error")
            }
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildSelectAid(aid: ByteArray): ByteArray {
        // CLA=00, INS=A4, P1=04, P2=00, Lc=aid.size, aid
        val apdu = ByteArray(5 + aid.size)
        apdu[0] = 0x00
        apdu[1] = 0xA4.toByte()
        apdu[2] = 0x04
        apdu[3] = 0x00
        apdu[4] = aid.size.toByte()
        System.arraycopy(aid, 0, apdu, 5, aid.size)
        return apdu
    }

    private fun buildApdu(
        ins: Byte,
        data: ByteArray,
    ): ByteArray {
        // CLA=00, INS, P1=00, P2=00, Lc=data.size, data
        val apdu = ByteArray(5 + data.size)
        apdu[0] = 0x00
        apdu[1] = ins
        apdu[2] = 0x00
        apdu[3] = 0x00
        apdu[4] = data.size.toByte()
        System.arraycopy(data, 0, apdu, 5, data.size)
        return apdu
    }

    private fun isSuccess(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
}

/** Outcome of an NFC exchange attempt. */
sealed class NfcExchangeOutcome {
    data class Success(
        val result: MobileNfcExchangeResult,
    ) : NfcExchangeOutcome()

    data class RelayFallback(
        val exchangeId: ByteArray,
    ) : NfcExchangeOutcome()

    data class Error(
        val message: String,
    ) : NfcExchangeOutcome()
}
