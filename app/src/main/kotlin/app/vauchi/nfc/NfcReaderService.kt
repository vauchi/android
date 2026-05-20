// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.MobileNfcExchangeResult
import uniffi.vauchi_platform.MobileNfcHandshake

/**
 * Drives NFC contact-exchange flows in two modes.
 *
 * **Legacy mode** (`performExchange(tag:session:)`): owns the
 * 3-phase handshake state-machine in Kotlin via `MobileNfcHandshake`.
 * Used by `NfcExchangeScreen` until the view migrates to
 * `CoreScreenView` over core's `exchange_nfc.rs` sub-flow
 * (Phase 4 of the engine-graduation).
 *
 * **Transceive-shim mode** (`activate(payload:callback:)` +
 * `onTagDiscovered(tag:)` + `sendApdu(data:)` + `deactivate()`):
 * pure APDU transceive on the `ExchangeCommandHandler` dispatch
 * path per ADR-031. Core's `NfcExchangeFlow`
 * (`core/vauchi-app/src/ui/exchange_nfc.rs`) owns the handshake
 * state-machine; this service just relays bytes in and
 * `MobileEvent.NfcDataReceived` out.
 *
 * The two modes coexist on one class because the consumer
 * (`NfcExchangeScreen` vs `ExchangeCommandHandler`) instantiates
 * its own dedicated instance — no single instance ever runs both
 * flows at once.
 *
 * Reader-mode lifecycle (Activity-level `enableReaderMode` +
 * tag-discovery callback) stays in the screen layer for both modes.
 * Legacy mode calls `performExchange(tag, session)` from the
 * callback; transceive-shim mode calls `onTagDiscovered(tag)` —
 * full plumbing into a production reader-mode lifecycle is gated
 * on Phase 4 view retirement (which also requires core
 * `ExchangeMode::Nfc` + `start_nfc_mode` entry path).
 */
class NfcReaderService {
    companion object {
        private const val TAG = "NfcReader"

        /** Maximum transceive timeout (ms) */
        private const val TRANSCEIVE_TIMEOUT_MS = 5000
    }

    // ── Transceive-shim state (ADR-031 — ExchangeCommandHandler consumer) ──

    private var transceiveIsoDep: IsoDep? = null
    private var transceiveCallback: ((MobileEvent) -> Unit)? = null
    private var pendingActivatePayload: ByteArray? = null

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
            Log.d(TAG, "Exchange complete")
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

    // ── Transceive-shim API (ADR-031 — ExchangeCommandHandler consumer) ──

    /**
     * Stash the initial APDU + callback; the actual NFC reader-mode
     * lifecycle (Activity-level [android.nfc.NfcAdapter.enableReaderMode])
     * stays in the screen layer. The screen routes its tag-discovery
     * callback to [onTagDiscovered] once a peer device taps; that's
     * where [payload] gets transceived as the first APDU.
     *
     * Called from `ExchangeCommandHandler` on `MobileCommand.NfcActivate`.
     * Idempotent against re-activation — replaces any in-flight callback
     * and pending payload.
     *
     * On hardware-unavailable platforms (no NFC adapter, NFC disabled),
     * the screen layer is responsible for surfacing
     * [MobileEvent.HardwareUnavailable]; this service can't detect
     * adapter state without an Activity reference.
     */
    fun activate(
        payload: ByteArray,
        callback: (MobileEvent) -> Unit,
    ) {
        transceiveCallback = callback
        pendingActivatePayload = payload
    }

    /**
     * Called by the screen layer when [android.nfc.NfcAdapter.ReaderCallback]
     * fires with a discovered tag. Connects via IsoDep and transceives
     * the pending activate payload. Response bytes surface via
     * `callback(MobileEvent.NfcDataReceived(data:))`.
     *
     * The screen layer owns reader-mode enable/disable; this method is
     * the bridge from a discovered [Tag] to the engine's hardware-event
     * channel. Phase 4 view retirement collapses the screen's
     * `performExchange(tag, session)` call into this method.
     */
    fun onTagDiscovered(tag: Tag) {
        val callback = transceiveCallback ?: return
        val isoDep =
            IsoDep.get(tag) ?: run {
                callback(MobileEvent.HardwareError("NFC", "tag does not support IsoDep"))
                return
            }
        try {
            isoDep.connect()
            isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
        } catch (e: Exception) {
            callback(MobileEvent.HardwareError("NFC", e.message ?: "connect failed"))
            return
        }
        transceiveIsoDep = isoDep
        val payload = pendingActivatePayload
        if (payload != null) {
            pendingActivatePayload = null
            transceiveOn(isoDep, payload, callback)
        }
    }

    /**
     * Send [data] as an APDU on the currently-connected tag. Response
     * bytes surface via `callback(MobileEvent.NfcDataReceived(data:))`.
     *
     * Called from `ExchangeCommandHandler` on `MobileCommand.NfcSendApdu`.
     * Invoking before [onTagDiscovered] connected a tag is a programming
     * error from core's perspective — reported as a hardware error so
     * the engine can fail-fast rather than wedge.
     */
    fun sendApdu(data: ByteArray) {
        val callback = transceiveCallback ?: return
        val isoDep =
            transceiveIsoDep ?: run {
                callback(MobileEvent.HardwareError("NFC", "no tag connected"))
                return
            }
        transceiveOn(isoDep, data, callback)
    }

    /**
     * Close the IsoDep connection and clear all transceive state.
     *
     * Called from `ExchangeCommandHandler` on `MobileCommand.NfcDeactivate`.
     * Idempotent — safe to call when no connection is open.
     */
    fun deactivate() {
        try {
            transceiveIsoDep?.close()
        } catch (_: Exception) {
        }
        transceiveIsoDep = null
        transceiveCallback = null
        pendingActivatePayload = null
    }

    private fun transceiveOn(
        isoDep: IsoDep,
        data: ByteArray,
        callback: (MobileEvent) -> Unit,
    ) {
        // Mirror iOS Phase 2: every outbound APDU wraps with
        // INS_KEY_OFFER. The graduated responder (Phase 3b) will
        // remove INS routing; until then, legacy HCE will misroute
        // Phase-3 sends — Phase 3a stays wired-but-dead until both
        // the responder side and a core `ExchangeMode::Nfc` entry
        // path light up.
        val apdu = buildApdu(VauchiHceService.INS_KEY_OFFER, data)
        val response =
            try {
                isoDep.transceive(apdu)
            } catch (e: Exception) {
                callback(MobileEvent.HardwareError("NFC", e.message ?: "transceive failed"))
                return
            }
        callback(MobileEvent.NfcDataReceived(response))
    }
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
