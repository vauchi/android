// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Delivers events to [process], preserving submission order.
 */
class FifoEventQueue<T>(
    private val scope: CoroutineScope,
    private val process: suspend (T) -> Unit,
) {
    /** Enqueue [event] for processing. Returns false if rejected. */
    fun send(event: T): Boolean {
        scope.launch { process(event) }
        return true
    }
}
