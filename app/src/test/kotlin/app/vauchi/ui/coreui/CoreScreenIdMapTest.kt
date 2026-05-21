// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `unknown ids return null`() {
        assertNull(coreScreenIdToVariant("my_info"))
        assertNull(coreScreenIdToVariant("exchange"))
        assertNull(coreScreenIdToVariant(""))
        assertNull(coreScreenIdToVariant("nonsense_screen_id"))
    }

    // ───────────────────────── TOP_LEVEL_SCREEN_IDS ──────────────────────────

    @Test
    fun `top-level set includes canonical AppScreen screen_id values`() {
        assertTrue("my_info" in TOP_LEVEL_SCREEN_IDS)
        assertTrue("exchange" in TOP_LEVEL_SCREEN_IDS)
        assertTrue("more" in TOP_LEVEL_SCREEN_IDS)
    }

    @Test
    fun `top-level set includes engine-emitted contact_list id`() {
        // Without this, the bottom nav unmounts the moment the user
        // taps the Contacts tab — see the problem record cited in
        // the class docstring.
        assertTrue("contact_list" in TOP_LEVEL_SCREEN_IDS)
    }

    @Test
    fun `top-level set includes engine-emitted groups_list id`() {
        assertTrue("groups_list" in TOP_LEVEL_SCREEN_IDS)
    }

    @Test
    fun `top-level set excludes non-top-level screen ids`() {
        assertTrue("settings" !in TOP_LEVEL_SCREEN_IDS)
        assertTrue("help" !in TOP_LEVEL_SCREEN_IDS)
        assertTrue("contact_detail" !in TOP_LEVEL_SCREEN_IDS)
        assertTrue("duress_overview" !in TOP_LEVEL_SCREEN_IDS)
    }
}
