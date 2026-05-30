// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.nfc

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

    fun sendApdu(data: ByteArray)

    fun deactivate()
}

/**
 * Dispatch an NFC [ExchangeCommand][CommandDTO] to the reader (initiator)
 * side. Returns `true` if [cmd] was an NFC command handled here, `false`
 * otherwise (the caller logs/ignores). Hardware events the reader surfaces
 * (`MobileEvent.NfcDataReceived`, errors) are routed to [onEvent], which
 * the ViewModel forwards to `PlatformAppEngine.handleHardwareEvent`.
 *
 * The HCE responder side (registering a `VauchiHceService.TransceiveContext`)
 * is dispatched separately — see T1.3. Initiator-vs-responder role
 * negotiation is an open design question and not decided here.
 */
fun dispatchNfcCommand(
    cmd: CommandDTO,
    reader: NfcReaderPort,
    onEvent: (MobileEvent) -> Unit,
): Boolean {
    when (cmd) {
        is CommandDTO.NfcActivate -> reader.activate(cmd.payload.toByteArrayFromInts(), onEvent)
        is CommandDTO.NfcSendApdu -> reader.sendApdu(cmd.data.toByteArrayFromInts())
        is CommandDTO.NfcDeactivate -> reader.deactivate()
        else -> return false
    }
    return true
}

/** Core serializes APDU/payload bytes as a JSON array of `u8` → `List<Int>`. */
private fun List<Int>.toByteArrayFromInts(): ByteArray = ByteArray(size) { this[it].toByte() }
