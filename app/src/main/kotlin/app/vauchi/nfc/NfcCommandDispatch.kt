// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

import android.nfc.Tag
import app.vauchi.ui.coreui.CommandDTO
import uniffi.vauchi_platform.MobileEvent

/**
 * Initiator-side seam for NFC exchange: the surface [NfcReaderService]
 * exposes to the command dispatcher. Extracted so the command→reader
 * mapping is unit-testable without an Activity / live NFC adapter
 * (T1.1 of `2026-05-29-nfc-exchange-mode-entry-wiring`).
 */
interface NfcReaderPort {
    fun activate(
        payload: ByteArray,
        callback: (MobileEvent) -> Unit,
    )

    /** Forward a tag discovered by the Activity's reader-mode callback (T1.2). */
    fun onTagDiscovered(tag: Tag)

    fun sendApdu(data: ByteArray)

    fun deactivate()
}

/**
 * Responder-side seam: the surface [VauchiHceService]'s HCE
 * transceive-shim exposes to the dispatcher (T1.3). Extracted (mirroring
 * [NfcReaderPort]) so the responder routing is unit-testable without a
 * live `HostApduService`.
 */
interface NfcResponderPort {
    /**
     * Register the HCE responder context. [onApdu] is invoked off the
     * binder thread with each inbound APDU; its owner drives the engine
     * (`handleHardwareEvent(NfcDataReceived)`).
     */
    fun register(onApdu: (ByteArray) -> Unit)

    /**
     * Fulfil an in-flight HCE binder block with [bytes]. Returns `true` if
     * a block was waiting (responder/HCE path active), `false` otherwise
     * (caller falls through to the initiator-side reader transceive).
     */
    fun fulfill(bytes: ByteArray): Boolean

    /** Clear the responder context (idempotent). */
    fun clear()
}

/** Production [NfcResponderPort] backed by [VauchiHceService]'s companion state. */
class VauchiHceResponder : NfcResponderPort {
    override fun register(onApdu: (ByteArray) -> Unit) {
        VauchiHceService.activeTransceiveContext = VauchiHceService.TransceiveContext(onApdu)
    }

    override fun fulfill(bytes: ByteArray): Boolean = VauchiHceService.fulfillPendingResponse(bytes)

    override fun clear() {
        VauchiHceService.clearActiveTransceiveContext()
    }
}

/**
 * Dispatch an NFC [ExchangeCommand][CommandDTO] to the initiator
 * ([reader]) or responder ([responder]) side. Returns `true` if [cmd] was
 * an NFC command handled here, `false` otherwise (the caller logs/ignores).
 *
 * Role is read off the `NfcActivate` payload: **empty** = responder
 * ("Receive" — register the HCE context; each inbound APDU drives the
 * engine as `NfcDataReceived` via [onEvent], whose `NfcSendApdu` reply
 * returns through [NfcResponderPort.fulfill]); **non-empty** = initiator
 * ("Send" — transceive the key offer and signal [onReaderMode]`(true)` so
 * the Activity enables NFC reader-mode). An `NfcSendApdu` first tries to
 * fulfil an in-flight HCE block; if none is active we are the initiator,
 * so it transceives on the reader. `NfcDeactivate` tears down both sides
 * and signals [onReaderMode]`(false)`. Hardware events the reader
 * surfaces are routed to [onEvent].
 */
fun dispatchNfcCommand(
    cmd: CommandDTO,
    reader: NfcReaderPort,
    responder: NfcResponderPort,
    onReaderMode: (Boolean) -> Unit,
    onEvent: (MobileEvent) -> Unit,
): Boolean {
    when (cmd) {
        is CommandDTO.NfcActivate -> {
            if (cmd.payload.isEmpty()) {
                responder.register { apdu -> onEvent(MobileEvent.NfcDataReceived(apdu)) }
            } else {
                reader.activate(cmd.payload.toByteArrayFromInts(), onEvent)
                onReaderMode(true)
            }
        }

        is CommandDTO.NfcSendApdu -> {
            val data = cmd.data.toByteArrayFromInts()
            if (!responder.fulfill(data)) {
                reader.sendApdu(data)
            }
        }

        is CommandDTO.NfcDeactivate -> {
            responder.clear()
            reader.deactivate()
            onReaderMode(false)
        }

        else -> {
            return false
        }
    }
    return true
}

/** Core serializes APDU/payload bytes as a JSON array of `u8` → `List<Int>`. */
private fun List<Int>.toByteArrayFromInts(): ByteArray = ByteArray(size) { this[it].toByte() }
