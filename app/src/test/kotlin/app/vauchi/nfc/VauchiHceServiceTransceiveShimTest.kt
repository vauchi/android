// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

// Unit tests for the transceive-shim API added in Phase 3b of the
// NFC engine-graduation
// (_private/docs/problems/2026-05-19-nfc-exchange-engine-graduation
// and the sibling sync-boundary record
// _private/docs/problems/2026-05-20-nfc-hce-responder-sync-boundary).
//
// Scope: the parts of the new transceive-shim companion API
// (`fulfillPendingResponse` / `clearActiveTransceiveContext`) that
// are testable without an OS-driven HCE invocation of
// `processCommandApdu`. End-to-end binder-thread block verification
// belongs to Phase 6 physical-device cycles per CC-23 — the
// `processCommandApdu` path requires a real HCE OS callback that
// neither Robolectric nor instrumentation can fake.
//
// Slice 32m migration: `TransceiveContext` no longer wraps the
// retired `MobileExchangeSession`; it carries an `onApduReceived`
// callback (the engine-driven owner is wired in the deferred
// functional-NFC follow-up). These fulfill/clear tests never invoke
// the callback, so a no-op `{}` suffices.

package app.vauchi.nfc

import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VauchiHceServiceTransceiveShimTest {
    @After
    fun tearDown() {
        VauchiHceService.activeTransceiveContext = null
    }

    /**
     * `fulfillPendingResponse` on a fresh state (no transceive
     * context registered) must return false so the initiator-side
     * `NfcReaderService.sendApdu` path can take over.
     */
    @Test
    fun `fulfillPendingResponse without active context returns false`() {
        assertNull(VauchiHceService.activeTransceiveContext)
        val ok = VauchiHceService.fulfillPendingResponse(byteArrayOf(0x01, 0x02))
        assertFalse(
            "fulfillPendingResponse must return false when no HCE flow is active",
            ok,
        )
    }

    /**
     * When a transceive context exists but no binder is currently
     * waiting on a deferred (between APDUs), `fulfillPendingResponse`
     * still returns false — there's nothing to fulfill.
     */
    @Test
    fun `fulfillPendingResponse with context but no pending deferred returns false`() {
        VauchiHceService.activeTransceiveContext =
            VauchiHceService.TransceiveContext(onApduReceived = {})

        val ok = VauchiHceService.fulfillPendingResponse(byteArrayOf(0x90.toByte(), 0x00))

        assertFalse(
            "fulfillPendingResponse must return false when no deferred is pending",
            ok,
        )
    }

    /**
     * When a binder thread has registered a pending deferred,
     * `fulfillPendingResponse` completes it with the supplied bytes
     * and returns true. The deferred consumer (the blocked binder)
     * then receives those bytes via `await`. The pendingResponse
     * slot clears so a subsequent fulfill on the same context
     * doesn't double-complete.
     */
    @Test
    fun `fulfillPendingResponse completes deferred and clears slot`() {
        val ctx = VauchiHceService.TransceiveContext(onApduReceived = {})
        val deferred = CompletableDeferred<ByteArray>()
        ctx.pendingResponse = deferred
        VauchiHceService.activeTransceiveContext = ctx

        val payload = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x90.toByte(), 0x00)
        val ok = VauchiHceService.fulfillPendingResponse(payload)

        assertTrue("fulfillPendingResponse must return true when a deferred is pending", ok)
        assertTrue("deferred must be completed after fulfill", deferred.isCompleted)
        assertArrayEquals(
            "deferred consumer must receive the supplied bytes",
            payload,
            deferred.getCompleted(),
        )
        assertNull(
            "pendingResponse slot must clear so subsequent fulfills don't double-complete",
            ctx.pendingResponse,
        )

        // Second fulfill on the same context is a no-op.
        val ok2 = VauchiHceService.fulfillPendingResponse(byteArrayOf(0x00))
        assertFalse("second fulfillPendingResponse must return false (slot cleared)", ok2)
    }

    /**
     * `clearActiveTransceiveContext` is idempotent and signals via
     * its return value whether a context was active. When a binder
     * is still blocked on a deferred at deactivation time, clear
     * must release it with `SW_OK` (`0x90 0x00`) so the OS path
     * doesn't hang on the ~125ms HCE deadline.
     */
    @Test
    fun `clearActiveTransceiveContext releases waiting binder and is idempotent`() {
        val ctx = VauchiHceService.TransceiveContext(onApduReceived = {})
        val deferred = CompletableDeferred<ByteArray>()
        ctx.pendingResponse = deferred
        VauchiHceService.activeTransceiveContext = ctx

        val cleared = VauchiHceService.clearActiveTransceiveContext()
        assertTrue("clearActiveTransceiveContext must return true when a context was set", cleared)
        assertNull(
            "activeTransceiveContext must be null after clear",
            VauchiHceService.activeTransceiveContext,
        )
        assertTrue(
            "blocked binder's deferred must be released so OS deadline isn't hit",
            deferred.isCompleted,
        )
        assertArrayEquals(
            "deferred must complete with SW_OK so the OS sees a clean response",
            byteArrayOf(0x90.toByte(), 0x00),
            deferred.getCompleted(),
        )

        // Second clear is a no-op.
        val cleared2 = VauchiHceService.clearActiveTransceiveContext()
        assertFalse(
            "second clearActiveTransceiveContext must return false (no context active)",
            cleared2,
        )
    }
}
