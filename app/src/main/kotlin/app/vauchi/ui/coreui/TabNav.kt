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

/** Outcome of attempting to replay a queued startup nav once tabs load. */
sealed interface TabNavFlush {
    data class Replay(
        val actionId: String,
    ) : TabNavFlush

    /** Tabs still empty — keep the request queued for the next load. */
    data object Keep : TabNavFlush

    /** A navigation with real intent happened since the queue — drop silently. */
    data class DropSuperseded(
        val currentScreenId: String,
    ) : TabNavFlush

    /** Tabs are loaded but the id is genuinely absent — drop with an error. */
    data class DropUnknown(
        val canonicalId: String,
    ) : TabNavFlush
}

/**
 * Decide whether the queued startup nav [pendingId] may replay.
 *
 * A queued nav is a *default-landing courtesy*: it may only replay while
 * the app still rests on core's bootstrap screen — [currentScreenId] null
 * (first screen not yet delivered) or core's post-identity default screen.
 * Any other screen means a navigation with real intent landed
 * between queue and flush (deep-link consent, a programmatic settings
 * nav, a user tap) and replaying would clobber it
 * (`2026-07-01-android-startup-nav-race-no-tab`, review finding).
 */
fun decideTabNavFlush(
    pendingId: String,
    tabs: List<MobileTabInfo>,
    currentScreenId: String?,
): TabNavFlush {
    // TODO(HUMBLE): W, P2. Hardcodes "my_info" bootstrap screen id. Fix:
    // core exposes isBootstrapScreen flag. (see _private problem record
    // 2026-07-06-mobile-domain-shell-violations)
    if (currentScreenId != null && currentScreenId != "my_info") {
        return TabNavFlush.DropSuperseded(currentScreenId)
    }
    return when (val decision = decideTabNav(tabs, pendingId)) {
        is TabNavDecision.Dispatch -> TabNavFlush.Replay(decision.actionId)
        is TabNavDecision.Queue -> TabNavFlush.Keep
        is TabNavDecision.Unknown -> TabNavFlush.DropUnknown(pendingId)
    }
}
