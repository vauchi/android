// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.MobileExchangeSession
import uniffi.vauchi_platform.MobileNfcHandshake

/**
 * Android Host Card Emulation service for NFC contact exchange.
 *
 * Drives the responder (card side) of the 3-phase encrypted handshake.
 * Two modes coexist:
 *
 * **Legacy mode** (`activeSession: MobileNfcHandshake?`): the
 * 3-phase handshake state-machine lives in Kotlin via the
 * `MobileNfcHandshake` UniFFI Object. APDU routing branches on the
 * INS byte (`INS_KEY_OFFER` / `INS_GET_ENCRYPTED_CARD` /
 * `INS_ENCRYPTED_CARD`). Used by `NfcExchangeScreen` until Phase 4
 * view retirement migrates it to `CoreScreenView`.
 *
 * **Transceive-shim mode** (`activeTransceiveContext`): pure APDU
 * relay onto core's `ExchangeSession` per ADR-031. Core's
 * `NfcExchangeFlow` owns the state machine; this service forwards
 * inbound bytes as `Event.NfcDataReceived` and blocks the binder
 * thread on a one-shot `CompletableDeferred<ByteArray>` that the
 * matching `Command.NfcSendApdu` dispatch arm fulfills. Pattern is
 * Option 4 of `2026-05-20-nfc-hce-responder-sync-boundary` —
 * in-ADR, binder-thread block on the existing event/command
 * channel, no new UniFFI surface.
 *
 * Static discriminator: when `activeTransceiveContext != null`,
 * `processCommandApdu` takes the transceive-shim path; otherwise
 * it falls back to the legacy `activeSession` path. Android HCE
 * is single-tap-at-a-time per device, so a single global context
 * is sufficient.
 */
class VauchiHceService : HostApduService() {
    /**
     * State carried across the binder-thread block: the engine
     * session that processes [MobileEvent.NfcDataReceived], the
     * `ExchangeCommandHandler.drainAndDispatch` hook, and a slot
     * for the in-flight `CompletableDeferred<ByteArray>` that
     * `Command.NfcSendApdu` will fulfill.
     */
    class TransceiveContext(
        val session: MobileExchangeSession,
        val drainAndDispatch: () -> Unit,
    ) {
        @Volatile
        var pendingResponse: CompletableDeferred<ByteArray>? = null
    }

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
         * Legacy mode session. Set by the UI before exchange starts.
         */
        @Volatile
        var activeSession: MobileNfcHandshake? = null

        /**
         * Transceive-shim mode context. Set by `NfcExchangeScreen` /
         * `ExchangeCommandHandler` before an HCE-driven exchange, cleared
         * after. When set, [processCommandApdu] routes via the
         * binder-block pattern; when null, falls back to legacy mode.
         */
        @Volatile
        var activeTransceiveContext: TransceiveContext? = null

        /**
         * Hardware-bounded timeout for the binder-thread block, in ms.
         * Pixel 3a HCE OS-side timeout is ~125ms; this leaves ~15ms of
         * slack for the engine's `handle_event` → `drain_commands`
         * latency (sub-millisecond in benches per HCE sibling record).
         */
        const val BINDER_BLOCK_TIMEOUT_MS: Long = 110

        /**
         * Worker scope for driving [MobileExchangeSession.applyHardwareEvent]
         * off the binder thread. Daemon by virtue of Dispatchers.IO —
         * does not block JVM shutdown.
         */
        private val workerScope = CoroutineScope(Dispatchers.IO)

        /**
         * Try to fulfill the in-flight binder block with [bytes].
         * Returns true if a pending response was waiting (HCE
         * transceive-shim path); false if no HCE flow is active
         * (caller falls through to the initiator-side
         * `NfcReaderService.sendApdu`). Called from
         * `ExchangeCommandHandler` on `MobileCommand.NfcSendApdu`.
         */
        fun fulfillPendingResponse(bytes: ByteArray): Boolean {
            val ctx = activeTransceiveContext ?: return false
            val deferred = ctx.pendingResponse ?: return false
            ctx.pendingResponse = null
            deferred.complete(bytes)
            return true
        }

        /**
         * Clear the active transceive context (called from
         * `ExchangeCommandHandler` on `MobileCommand.NfcDeactivate`).
         * Returns true if a context was cleared (signals to the caller
         * that the HCE path was active); false if no context was set.
         * Idempotent — safe to call when no context is registered.
         */
        fun clearActiveTransceiveContext(): Boolean {
            val ctx = activeTransceiveContext ?: return false
            activeTransceiveContext = null
            // If a binder thread is still waiting, unblock it with the
            // canonical OK SW so it returns cleanly to the OS rather
            // than timing out — Phase 3 terminal ACK is normally
            // emitted by `exchange_nfc.rs:handle_ack_sent` before
            // `NfcDeactivate`, but defensive against early teardown.
            ctx.pendingResponse?.let { deferred ->
                ctx.pendingResponse = null
                deferred.complete(SW_OK)
            }
            return true
        }
    }

    /** Encrypted card bytes from Phase 2, held until reader fetches with INS_GET_ENCRYPTED_CARD */
    private var pendingEncryptedCard: ByteArray? = null

    override fun processCommandApdu(
        commandApdu: ByteArray,
        extras: Bundle?,
    ): ByteArray {
        if (commandApdu.size < 4) return SW_WRONG_DATA

        // SELECT AID is identical across both modes — return SW_OK
        // before any state-machine dispatch.
        if (isSelectAid(commandApdu)) {
            return SW_OK
        }

        // Transceive-shim mode takes precedence when active.
        val ctx = activeTransceiveContext
        if (ctx != null) {
            return processViaTransceiveShim(ctx, commandApdu)
        }

        // Legacy mode (NfcExchangeScreen consumer until Phase 4
        // view retirement).
        val session = activeSession ?: return SW_CONDITIONS_NOT_SATISFIED
        return try {
            val ins = commandApdu[1]
            val data = extractData(commandApdu)
            when (ins) {
                INS_KEY_OFFER -> handleKeyOffer(session, data)
                INS_GET_ENCRYPTED_CARD -> handleGetEncryptedCard()
                INS_ENCRYPTED_CARD -> handleEncryptedCard(session, data)
                else -> SW_WRONG_DATA
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy APDU dispatch failed: ${e.javaClass.simpleName}")
            SW_CONDITIONS_NOT_SATISFIED
        }
    }

    /**
     * Binder-thread block pattern (Option 4 of
     * `2026-05-20-nfc-hce-responder-sync-boundary`). The binder
     * thread that invoked this method blocks on a one-shot
     * [CompletableDeferred] while a worker coroutine drives the
     * engine through `applyHardwareEvent` →
     * `drainPendingCommands` → `ExchangeCommandHandler.dispatch`.
     * The matching `Command.NfcSendApdu` dispatch arm fulfills
     * the deferred via [fulfillPendingResponse], unblocking this
     * binder thread.
     *
     * On timeout / cancellation: return `SW_CONDITIONS_NOT_SATISFIED`
     * so the OS-side error path triggers cleanly rather than the
     * binder thread hanging through the OS's ~125ms HCE deadline.
     */
    private fun processViaTransceiveShim(
        ctx: TransceiveContext,
        commandApdu: ByteArray,
    ): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        ctx.pendingResponse = deferred

        // Drive applyHardwareEvent + drain on a worker thread —
        // the binder thread is about to runBlocking on the deferred,
        // and the dispatcher must NOT run on this same thread or
        // the fulfill call would deadlock against runBlocking.
        workerScope.launch {
            try {
                ctx.session.applyHardwareEvent(MobileEvent.NfcDataReceived(commandApdu))
                ctx.drainAndDispatch()
            } catch (e: Exception) {
                Log.e(TAG, "HCE event apply failed: ${e.javaClass.simpleName}")
                deferred.completeExceptionally(e)
            }
        }

        return try {
            runBlocking {
                withTimeout(BINDER_BLOCK_TIMEOUT_MS) {
                    deferred.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            ctx.pendingResponse = null
            Log.e(TAG, "HCE binder block timed out after ${BINDER_BLOCK_TIMEOUT_MS}ms")
            SW_CONDITIONS_NOT_SATISFIED
        } catch (e: Exception) {
            ctx.pendingResponse = null
            Log.e(TAG, "HCE binder block failed: ${e.javaClass.simpleName}")
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
        Log.d(TAG, "Exchange complete")
        return SW_OK
    }
}
