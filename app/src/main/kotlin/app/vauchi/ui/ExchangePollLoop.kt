// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import kotlinx.coroutines.delay

/**
 * Default cadence for the multi-stage exchange tick. The protocol cycles
 * its multipart own-QR roughly every 300ms; polling a little faster keeps
 * the displayed frame current without lagging. Over-polling is cheap —
 * core's `advance` returns `None` until the per-frame window elapses.
 */
const val MULTI_STAGE_POLL_INTERVAL_MS = 200L

/**
 * Drive [tick] immediately, then once every [intervalMs], until the
 * calling coroutine is cancelled (e.g. the exchange screen leaves
 * composition). Replaces the core cycle thread retired in slice-32m
 * T1.2c: post-retirement the multi-stage machine advances only when the
 * frontend calls `PlatformAppEngine.pollNotifications`, and nothing was
 * calling it on a cadence while the exchange screen was open — so the
 * own-QR never appeared (Bug 5,
 * `_private/docs/problems/2026-05-30-exchange-screen-nav-visual-bugs`).
 *
 * Ticks first, then delays, so the QR shows on entry rather than one
 * interval later. Extracted from the composable to keep the cadence
 * unit-testable without a `PlatformAppEngine`.
 */
suspend fun pollLoop(
    intervalMs: Long = MULTI_STAGE_POLL_INTERVAL_MS,
    tick: suspend () -> Unit,
) {
    while (true) {
        tick()
        delay(intervalMs)
    }
}
