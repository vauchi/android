// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * The foreground wakeup loop (ADR-044 Am2a): run one tick, sleep for the
 * interval the tick returns, repeat. Core owns the interval; this owns only
 * the timer. [tick] returns the milliseconds until the next tick is due.
 */
internal class ForegroundHeartbeat(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val tick: suspend () -> Long,
) {
    private var job: Job? = null
    private val sooner = Channel<Long>(Channel.CONFLATED)

    /** Idempotent: a no-op while the loop is running. */
    fun start() {
        if (job?.isActive == true) return
        // A request from before this run belongs to a sleep that is gone.
        while (sooner.tryReceive().isSuccess) Unit
        job =
            scope.launch {
                while (isActive) {
                    sleep(tick())
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Core asked for a wakeup [intervalMs] from now, outside this loop's own
     * tick. The pending sleep is shortened to it, never lengthened: a session
     * that starts during the 30 s idle sleep would otherwise wait it out
     * before its first tick
     * (`2026-10-02-exchange-first-tick-waits-for-idle-heartbeat`).
     */
    fun reschedule(intervalMs: Long) {
        sooner.trySend(intervalMs)
    }

    private suspend fun sleep(intervalMs: Long) {
        var remaining = intervalMs
        while (remaining > 0) {
            val asleepSince = timeSource.markNow()
            val requested = withTimeoutOrNull(remaining) { sooner.receive() } ?: return
            remaining = minOf(remaining - asleepSince.elapsedNow().inWholeMilliseconds, requested)
        }
    }
}
