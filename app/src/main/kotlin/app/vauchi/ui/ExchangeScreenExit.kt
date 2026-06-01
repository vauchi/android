// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

/** Result of [exchangeExitDecision]: the new latch state and whether the
 * native exchange screen should hand control back to its parent. */
data class ExchangeExitDecision(
    val entered: Boolean,
    val shouldExit: Boolean,
)

/**
 * Decide whether a native exchange screen should exit because core has
 * navigated away from it.
 *
 * The native exchange screens (multi-stage QR, NFC) render core's
 * `ScreenModel` but live in the Activity's local `Screen` enum, not the
 * `CoreScreenView` dispatch — `coreScreenIdToVariant` returns null for
 * `exchange_*`. So when core's own navigation moves off the screen — e.g.
 * the Cancel action routes through the engine's `navigate_back` — nothing
 * pops the local enum and the screen looks frozen (Bug 2 of
 * `2026-05-30-exchange-screen-nav-visual-bugs`). The Activity observes
 * core's `screenId` through this helper and follows it off, mirroring
 * iOS's `FaceToFaceCoreShell.onChange { dismiss() }`.
 *
 * [ownScreenId] is the core `screen_id` this native screen drives. Once
 * core has been seen on it ([entered] latched true), any later id that
 * differs means core navigated away → exit. The latch guards the entry
 * race where the composable mounts (and sets the local enum) before
 * core's screen has actually become [ownScreenId].
 */
fun exchangeExitDecision(
    entered: Boolean,
    coreScreenId: String?,
    ownScreenId: String,
): ExchangeExitDecision =
    if (coreScreenId == ownScreenId) {
        ExchangeExitDecision(entered = true, shouldExit = false)
    } else {
        ExchangeExitDecision(entered = entered, shouldExit = entered)
    }
