// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import uniffi.vauchi_platform.MobileNfcExchangeResult
import uniffi.vauchi_platform.MobileNfcHandshake

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

            Log.d(
                TAG,
                "IsoDep connected, maxTransceiveLength=${isoDep.maxTransceiveLength}, isExtendedLengthApduSupported=${isoDep.isExtendedLengthApduSupported}",
            )

            // Step 1: SELECT AID
            val selectApdu = buildSelectAid(VauchiHceService.VAUCHI_AID)
            val selectResponse = isoDep.transceive(selectApdu)
            if (!isSuccess(selectResponse)) {
                Log.e(TAG, "AID selection failed, response=${selectResponse.joinToString("") { "%02x".format(it) }}")
                return NfcExchangeOutcome.Error("AID selection failed")
            }
            Log.d(TAG, "AID selected successfully")

            // Step 2a: Send key offer (Phase 1) → get key ack back
            val keyOffer = session.createKeyOffer()
            Log.d(TAG, "Key offer created, size=${keyOffer.size}")
            val offerApdu = buildApdu(VauchiHceService.INS_KEY_OFFER, keyOffer)
            Log.d(TAG, "Sending key offer APDU, size=${offerApdu.size}")
            val ackResponse = isoDep.transceive(offerApdu)
            Log.d(TAG, "Key ack response, size=${ackResponse.size}, sw=${ackResponse.takeLast(2).joinToString("") { "%02x".format(it) }}")
            if (!isSuccess(ackResponse)) {
                return NfcExchangeOutcome.Error("Key offer rejected (sw=${ackResponse.takeLast(2).joinToString("") { "%02x".format(it) }})")
            }
            val ackBytes = ackResponse.sliceArray(0 until ackResponse.size - 2)

            // Step 2b: Fetch encrypted card (Phase 2b)
            val getCardApdu = buildApdu(VauchiHceService.INS_GET_ENCRYPTED_CARD, ByteArray(0))
            Log.d(TAG, "Fetching encrypted card...")
            val cardResponse = isoDep.transceive(getCardApdu)
            Log.d(
                TAG,
                "Encrypted card response, size=${cardResponse.size}, sw=${cardResponse.takeLast(2).joinToString("") { "%02x".format(it) }}",
            )
            if (!isSuccess(cardResponse)) {
                return NfcExchangeOutcome.Error("Failed to get encrypted card")
            }
            val encryptedCard = cardResponse.sliceArray(0 until cardResponse.size - 2)

            // Step 3: Process key ack + encrypted card (Phase 2 initiator side)
            Log.d(TAG, "Processing key ack (${ackBytes.size} bytes) + encrypted card (${encryptedCard.size} bytes)")
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
        if (data.size <= 255) {
            // Short APDU: CLA=00, INS, P1=00, P2=00, Lc(1 byte), data
            val apdu = ByteArray(5 + data.size)
            apdu[0] = 0x00
            apdu[1] = ins
            apdu[2] = 0x00
            apdu[3] = 0x00
            apdu[4] = data.size.toByte()
            System.arraycopy(data, 0, apdu, 5, data.size)
            return apdu
        } else {
            // Extended APDU: CLA=00, INS, P1=00, P2=00, 0x00, Lc(2 bytes), data
            val apdu = ByteArray(7 + data.size)
            apdu[0] = 0x00
            apdu[1] = ins
            apdu[2] = 0x00
            apdu[3] = 0x00
            apdu[4] = 0x00 // extended length marker
            apdu[5] = (data.size shr 8).toByte()
            apdu[6] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, apdu, 7, data.size)
            return apdu
        }
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
