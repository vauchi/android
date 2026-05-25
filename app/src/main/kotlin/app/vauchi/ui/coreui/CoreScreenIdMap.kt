// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

/**
 * Map a core engine's emitted `ScreenModel.screen_id` to the
 * PascalCase `AppScreen` variant name that
 * [CoreAppViewModel.navigateTo] accepts. Returns `null` for ids that
 * either don't map to a Pure Humble UI screen (native cases handled
 * by the [Screen] enum arms) or aren't recognised.
 *
 * Engine-emitted ids are not identical to `AppScreen::screen_id()` —
 * each engine picks its own ScreenModel id, which is often the
 * engine's own name (e.g. `ContactListEngine` emits `"contact_list"`
 * while `AppScreen::Contacts.screen_id() == "contacts"`). This
 * function recognises both forms.
 *
 * The original 7 ids mirror the cases removed from `Screen` in the
 * 2026-04-30 Activity-enum-collapse Phase 1 — they all render through
 * the default `CoreScreenView` path. `MultiStageExchange` stays
 * native because it's a hardware-presentation wrapper (orientation
 * lock, brightness, keep-screen-on) around a `CoreScreenView`, not a
 * pure 1:1 shell. `decoy_contacts` was added in Phase 2c of
 * `2026-05-01-android-humble-ui-deep-retirement` so the core
 * DecoyContactsEngine renders when Settings → Decoy Contacts is
 * tapped.
 */
internal fun coreScreenIdToVariant(id: String): String? =
    when {
        // Canonical `AppScreen::screen_id()` ids.
        id == "contacts" -> "Contacts"

        id == "settings" -> "Settings"

        id == "device_management" -> "DeviceManagement"

        id == "groups" -> "Groups"

        id == "archived_contacts" -> "ArchivedContacts"

        id == "contact_duplicates" -> "ContactDuplicates"

        id == "device_replacement" -> "DeviceReplacement"

        id == "help" -> "Help"

        id == "more" -> "More"

        id == "decoy_contacts" -> "DecoyContacts"

        // Engine-emitted ids (engines whose ScreenModel id differs
        // from the canonical `AppScreen::screen_id()`). Without these
        // entries the bottom nav unmounts on Contacts and Groups and
        // the active-tab pill desyncs — see problem record
        // `2026-05-21-android-back-stack-and-bottom-nav-broken`.
        id == "contact_list" -> "Contacts"

        id == "groups_list" -> "Groups"

        // Multi-state engines: each engine drives multiple `screen_id`s
        // (e.g. DuressPinEngine cycles `duress_overview` →
        // `duress_enter_pin` → `duress_confirm_pin` → `duress_alerts`)
        // but all sub-states render via the same `CoreScreenView` for
        // the parent `AppScreen` variant. Prefix-match so adding a new
        // sub-state in core doesn't silently fall through to the
        // legacy `Screen.Home` fallback (F2-NEW-4 was exactly that:
        // `duress_overview` and `backup_choose` had no entry, so taps
        // on the Settings rows landed on My Card instead of the
        // requested screen — Decoy Contacts worked because it has
        // only the single `decoy_contacts` id mapped above).
        // Match both the per-sub-state engine ids (prefix) and the bare
        // canonical `AppScreen::screen_id()` core emits post
        // zero-domain-vocab Tier-0 (c). `backup` / `sync` have no
        // trailing underscore so the prefix arm misses them; `duress_pin`
        // already matches `startsWith("duress_")`.
        id.startsWith("duress_") -> "DuressPin"

        id == "backup" || id.startsWith("backup_") -> "Backup"

        id == "sync" || id.startsWith("sync_") -> "Sync"

        else -> null
    }

/**
 * Fold an engine-emitted `ScreenModel.screen_id` to its canonical
 * `AppScreen::screen_id()` form. Used by the bottom-nav pill match —
 * core's `tab_info` returns `tab.id == AppScreen::screen_id()` (e.g.
 * `"contacts"`) while the engine emits its own ScreenModel id (e.g.
 * `"contact_list"`). Without folding, the pill never highlights for
 * Contacts/Groups.
 *
 * Returns the input unchanged for ids that are already canonical or
 * not recognised — callers can treat the output as "the id to compare
 * against `tab.id`".
 */
internal fun canonicalScreenIdFor(engineEmittedId: String): String =
    when (engineEmittedId) {
        "contact_list" -> "contacts"
        "groups_list" -> "groups"
        else -> engineEmittedId
    }

/**
 * Core engine `screen_id`s for which the bottom navigation bar
 * should render. Includes both canonical `AppScreen::screen_id()`
 * values and the engine-emitted ids for engines that pick a
 * different ScreenModel id than the canonical one (see
 * [coreScreenIdToVariant]).
 *
 * Membership drives `isTopLevel` in MainActivity — when the active
 * core screen is in this set, the 5-tab `NavigationBar` renders.
 */
internal val TOP_LEVEL_SCREEN_IDS =
    setOf(
        // Canonical ids.
        "my_info",
        "exchange",
        "more",
        // Engine-emitted ids (see note in `coreScreenIdToVariant`).
        "contact_list",
        "groups_list",
    )
