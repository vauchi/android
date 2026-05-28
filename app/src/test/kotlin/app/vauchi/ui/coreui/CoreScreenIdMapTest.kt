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

    // ──── tab-rooted sub-screen echo arms ────
    //
    // Bug pin for `2026-05-25-contact-tap-opens-own-card`: tapping a
    // contact (or any tab-rooted sub-screen) silently nav-warped to My
    // Card because the engine-emitted `screen_id` ("contact_detail",
    // "edit_fields", "link_show_qr", …) had no entry in this map, so
    // `MainActivity.kt:638` fell through to `when (currentScreen) ->
    // Screen.Home -> ReadyScreen -> CoreScreenView(screenName="MyInfo",
    // navigateOnMount=true)` which actively *re-navigated* the engine
    // away from the deep screen. The fix: echo the engine's
    // `screen_id` itself for every tab-rooted sub-screen so the
    // top-level `CoreScreenView` arm renders. The RHS value is
    // decorative because the dispatch passes `navigateOnMount=false`.

    @Test
    fun `contact_detail echoes id (closes 2026-05-25-contact-tap-opens-own-card)`() {
        // The exact bug the problem record pinned. Without this arm,
        // tap "Bobsam" -> My Card on Pixel 3a (device QA 2026-05-27).
        assertEquals("contact_detail", coreScreenIdToVariant("contact_detail"))
    }

    @Test
    fun `contact_edit sub-screen ids echo (engine emits edit_fields, edit_visibility, edit_preview)`() {
        // contact_edit.rs:139/227/269 — the ContactEditEngine cycles
        // through three sub-screen states; all must render.
        assertEquals("edit_fields", coreScreenIdToVariant("edit_fields"))
        assertEquals("edit_visibility", coreScreenIdToVariant("edit_visibility"))
        assertEquals("edit_preview", coreScreenIdToVariant("edit_preview"))
    }

    @Test
    fun `my_info_entry_detail and group_detail echo`() {
        // README of the problem record explicitly names entry_detail
        // and group_detail as same-defect-class candidates.
        assertEquals("my_info_entry_detail", coreScreenIdToVariant("my_info_entry_detail"))
        assertEquals("group_detail", coreScreenIdToVariant("group_detail"))
    }

    @Test
    fun `other contact sub-screens echo (visibility, merge, limit, info, not_found)`() {
        // contact_visibility.rs:26, contact_merge.rs:83, contact_limit.rs:97,
        // contact_detail.rs:904 (contact_not_found). Same fallthrough class.
        assertEquals("contact_visibility", coreScreenIdToVariant("contact_visibility"))
        assertEquals("contact_merge", coreScreenIdToVariant("contact_merge"))
        assertEquals("contact_limit", coreScreenIdToVariant("contact_limit"))
        assertEquals("contact_info", coreScreenIdToVariant("contact_info"))
        assertEquals("contact_not_found", coreScreenIdToVariant("contact_not_found"))
    }

    @Test
    fun `device-link sub-screen ids echo (link_)`() {
        // device_linking.rs emits 13 distinct link_* screen_ids
        // (link_transport, link_show_qr, link_verify, link_complete, …).
        // All would silently nav-warp pre-fix.
        assertEquals("link_transport", coreScreenIdToVariant("link_transport"))
        assertEquals("link_show_qr", coreScreenIdToVariant("link_show_qr"))
        assertEquals("link_complete", coreScreenIdToVariant("link_complete"))
        assertEquals("link_failed", coreScreenIdToVariant("link_failed"))
    }

    @Test
    fun `device-replacement sub-screen ids echo (replacement_)`() {
        // device_replacement.rs emits replacement_* screen_ids.
        assertEquals("replacement_select_mode", coreScreenIdToVariant("replacement_select_mode"))
        assertEquals("replacement_show_qr", coreScreenIdToVariant("replacement_show_qr"))
        assertEquals("replacement_complete", coreScreenIdToVariant("replacement_complete"))
    }

    @Test
    fun `recovery sub-screen ids echo (recovery_)`() {
        // recovery_help, recovery_status, recovery_claim_review — these
        // are sub-screens under the recovery flow; the native
        // Screen.Recovery wrapper provides chrome but its CoreScreenView
        // inside reads these from viewModel.screen.
        assertEquals("recovery_help", coreScreenIdToVariant("recovery_help"))
        assertEquals("recovery_status", coreScreenIdToVariant("recovery_status"))
    }

    @Test
    fun `form dialog sub-screen ids echo (form_)`() {
        // form_dialog.rs emits form_add_field, form_edit_field,
        // form_edit_name, form_edit_relay_url for the SP form flow.
        assertEquals("form_add_field", coreScreenIdToVariant("form_add_field"))
        assertEquals("form_edit_field", coreScreenIdToVariant("form_edit_field"))
        assertEquals("form_edit_name", coreScreenIdToVariant("form_edit_name"))
    }

    @Test
    fun `emergency-shred sub-screen ids echo (shred_)`() {
        // emergency_shred.rs emits shred_warning/confirm/wiping/complete.
        assertEquals("shred_warning", coreScreenIdToVariant("shred_warning"))
        assertEquals("shred_confirm", coreScreenIdToVariant("shred_confirm"))
    }

    @Test
    fun `explicit-set sub-screen ids echo`() {
        // Sub-screens whose engine-emitted screen_id doesn't fall under
        // one of the prefixes but still needs to render via the top-level
        // CoreScreenView arm. Same fallthrough class as contact_detail.
        assertEquals("social_graph", coreScreenIdToVariant("social_graph"))
        assertEquals("delivery_status", coreScreenIdToVariant("delivery_status"))
        assertEquals("change_password", coreScreenIdToVariant("change_password"))
        assertEquals("privacy_settings", coreScreenIdToVariant("privacy_settings"))
        assertEquals("activity_log", coreScreenIdToVariant("activity_log"))
        assertEquals("fingerprint_verify", coreScreenIdToVariant("fingerprint_verify"))
        assertEquals("delete_identity_summary", coreScreenIdToVariant("delete_identity_summary"))
        assertEquals("deep_link_consent", coreScreenIdToVariant("deep_link_consent"))
    }

    @Test
    fun `contact_list still wins over the contact_ prefix (allow-list order preserved)`() {
        // The new `id.startsWith("contact_") -> id` arm comes after
        // `id == "contact_list" -> "Contacts"` so the canonical fold
        // for the Contacts tab is unchanged (regression guard).
        assertEquals("Contacts", coreScreenIdToVariant("contact_list"))
    }

    @Test
    fun `exchange_ screen_ids remain null (native MultiStageExchange shell wins)`() {
        // The exchange flow lives inside the native
        // MultiStageExchangeScreen wrapper which installs a BackHandler
        // that emits UserAction.ActionPressed("cancel") and provides
        // hardware-presentation chrome. Echoing exchange_* would unmount
        // that wrapper and route system-back through navigateBack instead
        // of the engine-level cancel — semantically different. Keep null.
        assertNull(coreScreenIdToVariant("exchange_mode_selection"))
        assertNull(coreScreenIdToVariant("exchange_show_qr"))
        assertNull(coreScreenIdToVariant("exchange_ble_discovering"))
    }
}
