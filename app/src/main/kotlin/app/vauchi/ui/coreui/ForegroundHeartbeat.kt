// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground wakeup loop (ADR-044 Am2a): run one tick, sleep for the
 * interval the tick returns, repeat. Core owns the interval; this owns only
 * the timer. [tick] returns the milliseconds until the next tick is due.
 */
internal class ForegroundHeartbeat(
    private val scope: CoroutineScope,
    private val tick: suspend () -> Long,
) {
    private var job: Job? = null

    /** Idempotent: a no-op while the loop is running. */
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                while (isActive) {
                    delay(tick())
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
