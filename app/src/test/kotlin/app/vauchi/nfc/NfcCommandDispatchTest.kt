// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import android.nfc.Tag
import app.vauchi.ui.coreui.CommandDTO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent

/**
 * T1.1 + T1.3 + T1.2 — dispatch of NFC ExchangeCommands to the initiator
 * ([NfcReaderPort]) and responder ([NfcResponderPort]) sides. The role
 * discriminator is the `NfcActivate` payload: empty = responder (register
 * HCE), non-empty = initiator (reader-mode + transceive). The initiator
 * path also signals the Activity to enable reader-mode (T1.2); deactivate
 * signals disable.
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

        override fun onTagDiscovered(tag: Tag) = Unit

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

    // Collects reader-mode enable(true)/disable(false) signals.
    private val readerModes = mutableListOf<Boolean>()

    private fun dispatch(
        cmd: CommandDTO,
        reader: NfcReaderPort = FakeReader(),
        responder: NfcResponderPort = FakeResponder(),
        onEvent: (MobileEvent) -> Unit = {},
    ): Boolean = dispatchNfcCommand(cmd, reader, responder, { readerModes.add(it) }, onEvent)

    // ── Initiator ("Send") — non-empty NfcActivate payload ──

    @Test
    fun `NfcActivate with payload dispatches to reader activate and enables reader-mode`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatch(CommandDTO.NfcActivate(listOf(0xAA, 0x01)), reader, responder)
        assertTrue(handled)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x01), reader.activatedPayload)
        assertNull("responder not registered for initiator", responder.registeredOnApdu)
        assertEquals("initiator enables reader-mode", listOf(true), readerModes)
    }

    @Test
    fun `initiator NfcActivate callback routes hardware events to onEvent`() {
        val reader = FakeReader()
        val events = mutableListOf<MobileEvent>()
        dispatch(CommandDTO.NfcActivate(listOf(1)), reader) { events.add(it) }
        reader.activatedCallback!!.invoke(MobileEvent.NfcDataReceived(byteArrayOf(9, 8)))
        assertEquals(1, events.size)
        assertArrayEquals(byteArrayOf(9, 8), (events[0] as MobileEvent.NfcDataReceived).data)
    }

    // ── Responder ("Receive") — empty NfcActivate payload ──

    @Test
    fun `NfcActivate with empty payload registers HCE responder without reader-mode`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatch(CommandDTO.NfcActivate(emptyList()), reader, responder)
        assertTrue(handled)
        assertNull("initiator reader not activated for responder", reader.activatedPayload)
        assertTrue("responder context registered", responder.registeredOnApdu != null)
        assertTrue("responder must NOT enable reader-mode", readerModes.isEmpty())
    }

    @Test
    fun `responder onApdu drives engine via NfcDataReceived event`() {
        val responder = FakeResponder()
        val events = mutableListOf<MobileEvent>()
        dispatch(CommandDTO.NfcActivate(emptyList()), responder = responder) { events.add(it) }
        responder.registeredOnApdu!!.invoke(byteArrayOf(7, 7))
        assertEquals(1, events.size)
        assertArrayEquals(byteArrayOf(7, 7), (events[0] as MobileEvent.NfcDataReceived).data)
    }

    // ── NfcSendApdu routing: responder block first, else initiator ──

    @Test
    fun `NfcSendApdu fulfils HCE block when responder active`() {
        val reader = FakeReader()
        val responder = FakeResponder(fulfillResult = true)
        dispatch(CommandDTO.NfcSendApdu(listOf(1, 2, 3)), reader, responder)
        assertArrayEquals(byteArrayOf(1, 2, 3), responder.fulfilledBytes)
        assertNull("reader must not transceive when HCE block fulfilled", reader.sentApdu)
        assertTrue("NfcSendApdu must not touch reader-mode", readerModes.isEmpty())
    }

    @Test
    fun `NfcSendApdu transceives on reader when no HCE block active`() {
        val reader = FakeReader()
        val responder = FakeResponder(fulfillResult = false)
        dispatch(CommandDTO.NfcSendApdu(listOf(255, 0, 16)), reader, responder)
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 16), reader.sentApdu)
    }

    // ── Deactivate clears both sides and disables reader-mode ──

    @Test
    fun `NfcDeactivate clears responder, deactivates reader, disables reader-mode`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatch(CommandDTO.NfcDeactivate, reader, responder)
        assertTrue(handled)
        assertTrue(responder.cleared)
        assertTrue(reader.deactivated)
        assertEquals("deactivate disables reader-mode", listOf(false), readerModes)
    }

    @Test
    fun `non-NFC command is not handled`() {
        val reader = FakeReader()
        val responder = FakeResponder()
        val handled = dispatch(CommandDTO.AudioStop, reader, responder)
        assertFalse(handled)
        assertNull(reader.activatedPayload)
        assertNull(responder.registeredOnApdu)
        assertTrue(readerModes.isEmpty())
    }
}
