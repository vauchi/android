// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

/**
 * Map a snake_case core screen id (`AppScreen::screen_id()` output)
 * to the PascalCase `AppScreen` variant name that
 * [CoreAppViewModel.navigateTo] accepts. Returns `null` for ids that
 * either don't map to a Pure Humble UI screen (native cases handled
 * by their dedicated [Screen] enum arms) or aren't recognised.
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
        // 1:1 screen-id ↔ AppScreen variant mappings.
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
        id.startsWith("duress_") -> "DuressPin"

        id.startsWith("backup_") -> "Backup"

        id.startsWith("sync_") -> "Sync"

        else -> null
    }

/** Top-level core screen ids that show the bottom navigation bar. */
internal val TOP_LEVEL_SCREEN_IDS =
    setOf(
        "my_info",
        "contacts",
        "exchange",
        "groups",
        "more",
    )
