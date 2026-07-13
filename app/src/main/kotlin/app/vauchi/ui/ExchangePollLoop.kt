// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import kotlinx.coroutines.delay

/**
 * Generic periodic driver: run [tick] immediately, then once every
 * [intervalMs], until the calling coroutine is cancelled.
 *
 * Originally extracted for the multi-stage exchange poll loop. That loop
 * is now retired in favour of core-driven lifecycle/wakeup events
 * (ADR-044 Am2a), but the helper remains for any future short-lived
 * polling surface that is genuinely frontend-owned.
 */
suspend fun pollLoop(
    intervalMs: Long,
    tick: suspend () -> Unit,
) {
    while (true) {
        tick()
        delay(intervalMs)
    }
}
