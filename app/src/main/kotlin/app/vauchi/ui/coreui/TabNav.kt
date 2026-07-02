// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import uniffi.vauchi_platform.MobileTabInfo

/**
 * Outcome of resolving a canonical tab id against the current tab set.
 * Extracted from [CoreAppViewModel] so the resolve/queue decision is
 * unit-testable without a native `PlatformAppEngine`.
 */
sealed interface TabNavDecision {
    data class Dispatch(
        val actionId: String,
    ) : TabNavDecision

    data class Queue(
        val canonicalId: String,
    ) : TabNavDecision

    data class Unknown(
        val canonicalId: String,
    ) : TabNavDecision
}

/**
 * Resolve [canonicalId] against [tabs].
 *
 * An empty [tabs] means the async `loadTabs` has not populated yet — on
 * cold-start/state-restore the tab-load and the restore navigation race in
 * the same frame — so the request is [TabNavDecision.Queue]d for replay
 * once tabs arrive rather than dropped with an error. A non-empty set that
 * still lacks the id is a genuine [TabNavDecision.Unknown] tab.
 * (`2026-07-01-android-startup-nav-race-no-tab`.)
 */
fun decideTabNav(
    tabs: List<MobileTabInfo>,
    canonicalId: String,
): TabNavDecision {
    val actionId = tabs.firstOrNull { it.id == canonicalId }?.actionId
    return when {
        actionId != null -> TabNavDecision.Dispatch(actionId)
        tabs.isEmpty() -> TabNavDecision.Queue(canonicalId)
        else -> TabNavDecision.Unknown(canonicalId)
    }
}
