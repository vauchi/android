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
    ) = MobileTabInfo(id = id, actionId = actionId, label = id, icon = "", badgeCount = 0u, isHome = id == "home")

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

    // ── decideTabNavFlush — the queued nav is a default-landing courtesy ──

    @Test
    fun `flush replays when tabs resolve and the first screen has not loaded`() {
        val tabs = listOf(tab("contacts", "act-contacts"))

        assertEquals(
            TabNavFlush.Replay("act-contacts"),
            decideTabNavFlush("contacts", tabs, currentScreenId = null),
        )
    }

    @Test
    fun `flush replays while parked on core's bootstrap screen`() {
        val tabs = listOf(tab("contacts", "act-contacts"))

        assertEquals(
            TabNavFlush.Replay("act-contacts"),
            decideTabNavFlush("contacts", tabs, currentScreenId = "my_info"),
        )
    }

    @Test
    fun `flush drops silently when a real navigation landed in between`() {
        // Deep-link consent, a programmatic settings nav, or a user tap —
        // replaying the courtesy landing would clobber it.
        val tabs = listOf(tab("contacts", "act-contacts"))

        assertEquals(
            TabNavFlush.DropSuperseded("deep_link_consent"),
            decideTabNavFlush("contacts", tabs, currentScreenId = "deep_link_consent"),
        )
    }

    @Test
    fun `flush keeps the request queued while tabs are still empty`() {
        assertEquals(
            TabNavFlush.Keep,
            decideTabNavFlush("contacts", emptyList(), currentScreenId = null),
        )
    }

    @Test
    fun `flush errors only when tabs are loaded but the id is absent`() {
        val tabs = listOf(tab("home", "act-home"))

        assertEquals(
            TabNavFlush.DropUnknown("contacts"),
            decideTabNavFlush("contacts", tabs, currentScreenId = "my_info"),
        )
    }
}
