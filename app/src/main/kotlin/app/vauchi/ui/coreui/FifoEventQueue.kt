// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Delivers events to [process] strictly in submission order, one at a
 * time. BLE/ATT guarantees in-order notification delivery and core's
 * handshake machine depends on it (the KeyAck must precede the card
 * chunks it authenticates) — a coroutine-per-event dispatch re-orders
 * events that arrive milliseconds apart. See
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
class FifoEventQueue<T>(
    scope: CoroutineScope,
    private val process: suspend (T) -> Unit,
) {
    private val channel = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in channel) process(event)
        }
    }

    /** Enqueue [event] for processing. Returns false if rejected. */
    fun send(event: T): Boolean = channel.trySend(event).isSuccess
}
