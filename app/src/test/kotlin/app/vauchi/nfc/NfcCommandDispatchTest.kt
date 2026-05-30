// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import app.vauchi.ui.coreui.CommandDTO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent

/**
 * T1.1 + T1.3 — dispatch of NFC ExchangeCommands to the initiator
 * ([NfcReaderPort]) and responder ([NfcResponderPort]) sides. The role
 * discriminator is the `NfcActivate` payload: empty = responder (register
 * HCE), non-empty = initiator (reader-mode + transceive); and an
 * `NfcSendApdu` fulfils an in-flight HCE binder block when one is active,
 * otherwise transceives on the reader.
 */
class NfcCommandDispatchTest {
    private class FakeReader : NfcReaderPort {
        var activatedPayload: ByteArray? = null
        var activatedCallback: ((MobileEvent) -> Unit)? = null
        var sentApdu: ByteArray? = null
        var deactivated = false

        override fun activate(
            payload: ByteArray,
            callback: (MobileEvent) -> Unit,
        ) {
            activatedPayload = payload
            activatedCallback = callback
        }

        override fun sendApdu(data: ByteArray) {
            sentApdu = data
        }

        override fun deactivate() {
            deactivated = true
        }
    }

    private class FakeResponder(
        private val fulfillResult: Boolean = true,
    ) : NfcResponderPort {
        var registeredOnApdu: ((ByteArray) -> Unit)? = null
        var fulfilledBytes: ByteArray? = null
        var cleared = false

        override fun register(onApdu: (ByteArray) -> Unit) {
            registeredOnApdu = onApdu
        }

        override fun fulfill(bytes: ByteArray): Boolean {
            fulfilledBytes = bytes
            return fulfillResult
        }

        override fun clear() {
            cleared = true
        }
    }

    // ── Initiator ("Send") — non-empty NfcActivate payload ──

    @Test
    fun `NfcActivate with payload dispatches to reader activate (initiator)`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatchNfcCommand(CommandDTO.NfcActivate(listOf(0xAA, 0x01)), reader, responder) {}
        assertTrue(handled)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x01), reader.activatedPayload)
        assertNull("responder not registered for initiator", responder.registeredOnApdu)
    }

    @Test
    fun `initiator NfcActivate callback routes hardware events to onEvent`() {
        val reader = FakeReader()
        val events = mutableListOf<MobileEvent>()
        dispatchNfcCommand(CommandDTO.NfcActivate(listOf(1)), reader, FakeResponder()) { events.add(it) }
        reader.activatedCallback!!.invoke(MobileEvent.NfcDataReceived(byteArrayOf(9, 8)))
        assertEquals(1, events.size)
        assertArrayEquals(byteArrayOf(9, 8), (events[0] as MobileEvent.NfcDataReceived).data)
    }

    // ── Responder ("Receive") — empty NfcActivate payload ──

    @Test
    fun `NfcActivate with empty payload registers HCE responder (not reader)`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatchNfcCommand(CommandDTO.NfcActivate(emptyList()), reader, responder) {}
        assertTrue(handled)
        assertNull("initiator reader not activated for responder", reader.activatedPayload)
        assertTrue("responder context registered", responder.registeredOnApdu != null)
    }

    @Test
    fun `responder onApdu drives engine via NfcDataReceived event`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val events = mutableListOf<MobileEvent>()
        dispatchNfcCommand(CommandDTO.NfcActivate(emptyList()), reader, responder) { events.add(it) }
        // An inbound HCE APDU must reach the engine as NfcDataReceived.
        responder.registeredOnApdu!!.invoke(byteArrayOf(7, 7))
        assertEquals(1, events.size)
        assertArrayEquals(byteArrayOf(7, 7), (events[0] as MobileEvent.NfcDataReceived).data)
    }

    // ── NfcSendApdu routing: responder block first, else initiator ──

    @Test
    fun `NfcSendApdu fulfils HCE block when responder active`() {
        val reader = FakeReader()
        val responder = FakeResponder(fulfillResult = true)
        val handled = dispatchNfcCommand(CommandDTO.NfcSendApdu(listOf(1, 2, 3)), reader, responder) {}
        assertTrue(handled)
        assertArrayEquals(byteArrayOf(1, 2, 3), responder.fulfilledBytes)
        assertNull("reader must not transceive when HCE block fulfilled", reader.sentApdu)
    }

    @Test
    fun `NfcSendApdu transceives on reader when no HCE block active`() {
        val reader = FakeReader()
        val responder = FakeResponder(fulfillResult = false)
        dispatchNfcCommand(CommandDTO.NfcSendApdu(listOf(255, 0, 16)), reader, responder) {}
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 16), reader.sentApdu)
    }

    // ── Deactivate clears both sides ──

    @Test
    fun `NfcDeactivate clears responder and deactivates reader`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatchNfcCommand(CommandDTO.NfcDeactivate, reader, responder) {}
        assertTrue(handled)
        assertTrue(responder.cleared)
        assertTrue(reader.deactivated)
    }

    @Test
    fun `non-NFC command is not handled`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatchNfcCommand(CommandDTO.AudioStop, reader, responder) {}
        assertFalse(handled)
        assertNull(reader.activatedPayload)
        assertNull(responder.registeredOnApdu)
    }
}
