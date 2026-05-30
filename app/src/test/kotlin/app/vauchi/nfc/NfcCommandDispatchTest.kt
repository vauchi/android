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
 * T1.1 — initiator-side dispatch of NFC ExchangeCommands to [NfcReaderPort].
 * The role-negotiation question (who is initiator vs HCE responder) is
 * separate; this verifies only that, once core emits an NFC command, it
 * reaches the reader with the right bytes and routes responses back.
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

    @Test
    fun `NfcActivate dispatches payload bytes to reader activate`() {
        val reader = FakeReader()
        val handled = dispatchNfcCommand(CommandDTO.NfcActivate(listOf(0xAA, 0x01)), reader) {}
        assertTrue(handled)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x01), reader.activatedPayload)
    }

    @Test
    fun `NfcActivate callback routes hardware events back to onEvent`() {
        val reader = FakeReader()
        val events = mutableListOf<MobileEvent>()
        dispatchNfcCommand(CommandDTO.NfcActivate(listOf(1)), reader) { events.add(it) }
        // Reader surfaces a peer response on the stashed callback.
        reader.activatedCallback!!.invoke(MobileEvent.NfcDataReceived(byteArrayOf(9, 8)))
        assertEquals(1, events.size)
        assertTrue(events[0] is MobileEvent.NfcDataReceived)
        assertArrayEquals(byteArrayOf(9, 8), (events[0] as MobileEvent.NfcDataReceived).data)
    }

    @Test
    fun `NfcSendApdu dispatches data bytes to reader sendApdu`() {
        val reader = FakeReader()
        val handled = dispatchNfcCommand(CommandDTO.NfcSendApdu(listOf(255, 0, 16)), reader) {}
        assertTrue(handled)
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 16), reader.sentApdu)
    }

    @Test
    fun `NfcDeactivate calls reader deactivate`() {
        val reader = FakeReader()
        val handled = dispatchNfcCommand(CommandDTO.NfcDeactivate, reader) {}
        assertTrue(handled)
        assertTrue(reader.deactivated)
    }

    @Test
    fun `non-NFC command is not handled`() {
        val reader = FakeReader()
        val handled = dispatchNfcCommand(CommandDTO.AudioStop, reader) {}
        assertFalse(handled)
        assertNull(reader.activatedPayload)
        assertFalse(reader.deactivated)
    }
}
