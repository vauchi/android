// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import uniffi.vauchi_platform.MobileEvent

/**
 * Drives the NFC reader (initiator) side of contact exchange via the
 * transceive-shim API (`activate(payload:callback:)` +
 * `onTagDiscovered(tag:)` + `sendApdu(data:)` + `deactivate()`) — pure
 * APDU transceive on the `ExchangeCommandHandler` dispatch path per
 * ADR-031. Core's `NfcExchangeFlow`
 * (`core/vauchi-app/src/ui/exchange/nfc.rs`) owns the handshake
 * state-machine; this service just relays bytes in and
 * `MobileEvent.NfcDataReceived` out.
 *
 * Reader-mode lifecycle (Activity-level `enableReaderMode` +
 * tag-discovery callback) stays in the screen layer (the new
 * `NfcTapExchangeScreen` humble shell since Phase 4 of the engine
 * graduation; production wiring of the reader-mode callback is the
 * Phase 6 device-cycles deliverable).
 *
 * Legacy `performExchange(tag, session)` + the `NfcExchangeOutcome`
 * sealed class retired 2026-05-21 alongside `NfcExchangeScreen`
 * (Phase 4) and `NfcTestActivity` (Phase 5 prep). The remaining
 * binding-side consumer of `MobileNfcHandshake` is core's mobile_nfc.rs
 * itself, retired in the Phase 5 core MR.
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
