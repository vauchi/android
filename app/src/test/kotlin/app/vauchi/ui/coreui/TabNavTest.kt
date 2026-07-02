// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileTabInfo

/**
 * Unit test for [decideTabNav] — the resolve/queue decision behind
 * [CoreAppViewModel.navigateToTabById]. Extracted from the VM so it is
 * testable without a native `PlatformAppEngine` (same rationale as
 * `pollLoop` / `ScreenInvalidationListener`). Regression guard for
 * `2026-07-01-android-startup-nav-race-no-tab`: a restore/startup nav to
 * "contacts" fired before the async `loadTabs` had populated `_tabs`, so
 * the lookup missed, logged an error, and DROPPED a legitimate nav.
 */
class TabNavTest {
    private fun tab(
        id: String,
        actionId: String,
    ) = MobileTabInfo(id = id, actionId = actionId, label = id, icon = "", badgeCount = 0u)

    @Test
    fun `dispatches when the tab is present`() {
        val tabs = listOf(tab("home", "act-home"), tab("contacts", "act-contacts"))

        assertEquals(TabNavDecision.Dispatch("act-contacts"), decideTabNav(tabs, "contacts"))
    }

    @Test
    fun `queues (does not error) when tabs are not loaded yet`() {
        // Empty _tabs == async loadTabs has not completed — the startup race.
        assertEquals(TabNavDecision.Queue("contacts"), decideTabNav(emptyList(), "contacts"))
    }

    @Test
    fun `reports unknown only when tabs are loaded but the id is absent`() {
        val tabs = listOf(tab("home", "act-home"))

        assertEquals(TabNavDecision.Unknown("contacts"), decideTabNav(tabs, "contacts"))
    }
}
