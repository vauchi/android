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

/**
 * Android Host Card Emulation service for NFC contact exchange.
 *
 * Drives the responder (card side) of the 3-phase encrypted
 * handshake via the transceive-shim path: pure APDU relay onto
 * core's `ExchangeSession` per ADR-031. Core's `NfcExchangeFlow`
 * owns the state machine; this service forwards inbound bytes as
 * `Event.NfcDataReceived` and blocks the binder thread on a
 * one-shot `CompletableDeferred<ByteArray>` that the matching
 * `Command.NfcSendApdu` dispatch arm fulfills (Option 4 of
 * `2026-05-20-nfc-hce-responder-sync-boundary` — in-ADR,
 * binder-thread block on the existing event/command channel, no
 * new UniFFI surface).
 *
 * Single in-flight `TransceiveContext`: Android HCE is
 * single-tap-at-a-time per device, so one global context suffices.
 * `processCommandApdu` returns `SW_CONDITIONS_NOT_SATISFIED` when
 * no context is registered (no active exchange).
 *
 * Legacy `activeSession: MobileNfcHandshake?` mode + the INS-routed
 * `handleKeyOffer` / `handleGetEncryptedCard` / `handleEncryptedCard`
 * dispatchers retired 2026-05-21 alongside `NfcExchangeScreen`
 * (Phase 4) and `NfcTestActivity` (Phase 5 prep).
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

        // INS used by the NfcReaderService transceive-shim to wrap
        // outbound APDUs. Legacy INS_GET_ENCRYPTED_CARD / INS_ENCRYPTED_CARD
        // retired 2026-05-21 alongside the legacy responder dispatch.
        const val INS_KEY_OFFER: Byte = 0xE0.toByte()

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
            // emitted by `exchange/nfc.rs:handle_ack_sent` before
            // `NfcDeactivate`, but defensive against early teardown.
            ctx.pendingResponse?.let { deferred ->
                ctx.pendingResponse = null
                deferred.complete(SW_OK)
            }
            return true
        }
    }

    override fun processCommandApdu(
        commandApdu: ByteArray,
        extras: Bundle?,
    ): ByteArray {
        if (commandApdu.size < 4) return SW_WRONG_DATA

        // SELECT AID — handle outside the transceive-shim path so
        // dead taps that never establish a TransceiveContext still
        // get a clean SW_OK rather than an OS-side timeout.
        if (isSelectAid(commandApdu)) {
            return SW_OK
        }

        val ctx = activeTransceiveContext ?: return SW_CONDITIONS_NOT_SATISFIED
        return processViaTransceiveShim(ctx, commandApdu)
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

}
