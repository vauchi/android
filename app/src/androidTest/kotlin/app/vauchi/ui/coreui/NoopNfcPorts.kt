// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.nfc.Tag
import app.vauchi.nfc.NfcReaderPort
import app.vauchi.nfc.NfcResponderPort
import uniffi.vauchi_platform.MobileEvent

/**
 * No-op NFC ports keeping [CoreAppViewModel] construction off any device
 * NFC adapter — the screen trees under host-reachability guards never
 * drive NFC.
 */
object NoopNfcReader : NfcReaderPort {
    override fun activate(
        payload: ByteArray,
        callback: (MobileEvent) -> Unit,
    ) {}

    override fun onTagDiscovered(tag: Tag) {}

    override fun sendApdu(data: ByteArray) {}

    override fun deactivate() {}
}

object NoopNfcResponder : NfcResponderPort {
    override fun register(onApdu: (ByteArray) -> Unit) {}

    override fun fulfill(bytes: ByteArray): Boolean = false

    override fun clear() {}
}
