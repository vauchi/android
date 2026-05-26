// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the screen-id ↔ `AppScreen` variant mapping driving the
 * Android shell's `Path A` vs `Path B` dispatch and the bottom-nav
 * visibility guard.
 *
 * Regression coverage for the
 * `2026-05-21-android-back-stack-and-bottom-nav-broken` problem
 * record: `ContactListEngine` emits `screen_id = "contact_list"` and
 * `GroupsListEngine` emits `screen_id = "groups_list"`, but those
 * engines back the `AppScreen::Contacts` / `AppScreen::Groups`
 * variants. The shell must recognise both the canonical
 * `AppScreen::screen_id()` values *and* the engines' emitted ids;
 * otherwise the renderer falls through to the legacy `Screen.Home`
 * (ReadyScreen) wrapper, the bottom nav unmounts, and the
 * active-tab pill desyncs.
 */
class CoreScreenIdMapTest {
    // ───────────────────────── coreScreenIdToVariant ─────────────────────────

    @Test
    fun `canonical AppScreen screen_id values map to their variant name`() {
        assertEquals("Contacts", coreScreenIdToVariant("contacts"))
        assertEquals("Settings", coreScreenIdToVariant("settings"))
        assertEquals("Groups", coreScreenIdToVariant("groups"))
        assertEquals("More", coreScreenIdToVariant("more"))
        assertEquals("Help", coreScreenIdToVariant("help"))
    }

    @Test
    fun `contact_list (engine-emitted) maps to Contacts`() {
        // ContactListEngine emits screen_id="contact_list" while
        // AppScreen::Contacts.screen_id()="contacts". The shell must
        // bridge both ids to the same variant.
        assertEquals("Contacts", coreScreenIdToVariant("contact_list"))
    }

    @Test
    fun `groups_list (engine-emitted) maps to Groups`() {
        // GroupsListEngine emits screen_id="groups_list" while
        // AppScreen::Groups.screen_id()="groups". Same bridge as
        // contact_list above.
        assertEquals("Groups", coreScreenIdToVariant("groups_list"))
    }

    @Test
    fun `multi-state engine prefixes map to their parent variant`() {
        assertEquals("DuressPin", coreScreenIdToVariant("duress_overview"))
        assertEquals("DuressPin", coreScreenIdToVariant("duress_enter_pin"))
        assertEquals("Backup", coreScreenIdToVariant("backup_choose"))
        assertEquals("Sync", coreScreenIdToVariant("sync_status"))
    }

    @Test
    fun `bare canonical multi-state ids map to their parent variant`() {
        // Zero-domain-vocab Tier-0 (c) prereq: core is moving to emit the
        // canonical `AppScreen::screen_id()` (e.g. "backup", "sync",
        // "duress_pin") instead of the per-sub-state engine ids
        // ("backup_choose", "sync_status", ...). The `startsWith("backup_")`
        // / `startsWith("sync_")` arms miss the bare canonical ids because
        // they have no trailing underscore — so without these the Backup
        // and Sync screens would fall through to the native partition and
        // break once core flips. `duress_pin` already matches
        // `startsWith("duress_")`; pinned here so the contract is explicit.
        assertEquals("Backup", coreScreenIdToVariant("backup"))
        assertEquals("Sync", coreScreenIdToVariant("sync"))
        assertEquals("DuressPin", coreScreenIdToVariant("duress_pin"))
    }

    @Test
    fun `unknown ids return null`() {
        assertNull(coreScreenIdToVariant("my_info"))
        assertNull(coreScreenIdToVariant("exchange"))
        assertNull(coreScreenIdToVariant(""))
        assertNull(coreScreenIdToVariant("nonsense_screen_id"))
    }
}
