// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import kotlinx.coroutines.delay

/**
 * Default cadence for the multi-stage exchange tick. The protocol's
 * fastest QR frame is DATA at `display_duration_ms = 100ms ± 20%` (i.e.
 * windows as short as ~80ms). The poll rate caps how many *distinct*
 * frames core actually emits per second, because each `advance` emits at
 * most one frame: at the old 200ms a 100ms frame was held ~200ms, so the
 * displayed throughput was **half** what the protocol intends — and half
 * the retired cycle thread's rate, which drove frames at their own
 * duration. On a lossy receiver (Samsung S7) that halving starved chunk
 * delivery so the exchange reached "Almost done" but never finalized,
 * while the legacy cycle-thread build finalized on the same hardware
 * (device session 2026-06-03,
 * `_private/docs/problems/2026-06-03-multistage-qr-exchange-stalls-init-on-device`).
 *
 * Poll well under the shortest frame window so the per-frame gate — not
 * the poll — times each frame, restoring the cycle thread's throughput.
 * Over-polling is cheap: core's `advance` returns `None` until the
 * window elapses. The exchange is short-lived, so the higher tick rate
 * costs little.
 */
const val MULTI_STAGE_POLL_INTERVAL_MS = 50L

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
