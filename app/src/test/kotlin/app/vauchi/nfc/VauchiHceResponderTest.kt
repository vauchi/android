// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.3 — the production [VauchiHceResponder] adapter must install a live
 * [VauchiHceService.TransceiveContext] on the companion so the HCE service
 * routes inbound APDUs, and must clear it on teardown. Verified against the
 * real companion state (all static — no `HostApduService` instance needed).
 */
class VauchiHceResponderTest {
    @After
    fun tearDown() {
        // Companion state is process-global — don't leak across tests.
        VauchiHceService.clearActiveTransceiveContext()
    }

    @Test
    fun `register installs a context that forwards apdus to the callback`() {
        val responder = VauchiHceResponder()
        val received = mutableListOf<ByteArray>()

        responder.register { received.add(it) }

        val ctx = VauchiHceService.activeTransceiveContext
        assertNotNull("register must set the companion context", ctx)
        ctx!!.onApduReceived(byteArrayOf(1, 2, 3))
        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), received[0])
    }

    @Test
    fun `fulfill returns true when a binder block is pending, false otherwise`() {
        val responder = VauchiHceResponder()
        // No context / no pending block yet → nothing to fulfil.
        assertTrue("no responder active → not fulfilled", !responder.fulfill(byteArrayOf(9)))

        responder.register { }
        val ctx = VauchiHceService.activeTransceiveContext!!
        ctx.pendingResponse = kotlinx.coroutines.CompletableDeferred()
        assertTrue("pending block present → fulfilled", responder.fulfill(byteArrayOf(0x90.toByte(), 0x00)))
    }

    @Test
    fun `clear resets the companion context`() {
        val responder = VauchiHceResponder()
        responder.register { }
        assertNotNull(VauchiHceService.activeTransceiveContext)

        responder.clear()
        assertNull("clear must null the companion context", VauchiHceService.activeTransceiveContext)
    }
}
