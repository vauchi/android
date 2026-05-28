// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

/**
 * Map a core engine's emitted `ScreenModel.screen_id` to a non-null
 * value when the screen should render through the default
 * `CoreScreenView` path at `MainActivity.kt:638`. Returns `null` only
 * for native-shell-owned screens (the exchange flow, plus the boot /
 * onboarding `Screen.Home` and pure-native ExchangeModePicker).
 *
 * Two arm shapes, in `when` short-circuit order:
 *
 * 1. **Allow-list** (canonical `AppScreen::screen_id()` + a handful of
 *    engine-emitted folds): RHS is the PascalCase `AppScreen` variant
 *    name. Historical — left intact so existing callers and the
 *    bottom-nav active-pill logic remain unchanged.
 * 2. **Sub-screen echo arms** (added 2026-05-28 to close
 *    `2026-05-25-contact-tap-opens-own-card`): RHS is the input `id`
 *    itself. The value is decorative because the dispatch
 *    (`MainActivity.kt:642`) passes `navigateOnMount = false` to the
 *    `CoreScreenView` it constructs from this mapping — only the
 *    *non-null-ness* selects the top-level `CoreScreenView` branch.
 *    Without these, every engine-emitted sub-screen id (`contact_detail`,
 *    `edit_fields`, `link_show_qr`, …) returned `null` and fell through
 *    to `when (currentScreen) -> Screen.Home -> ReadyScreen ->
 *    CoreScreenView(screenName="MyInfo", navigateOnMount=true)`, which
 *    actively re-navigated the engine to My Card — visible to the user
 *    as "tap a contact, land on My Card".
 *
 * Engine-emitted ids are not identical to `AppScreen::screen_id()` —
 * each engine picks its own ScreenModel id (e.g. `ContactListEngine`
 * emits `"contact_list"` while `AppScreen::Contacts.screen_id() ==
 * "contacts"`). The allow-list bridges both forms; the echo arms
 * forward whatever id the engine stamped.
 *
 * `exchange_*` screen_ids deliberately stay null: the multi-stage
 * exchange runs inside the native `MultiStageExchangeScreen` /
 * `NfcTapExchangeScreen` wrappers which install a `BackHandler` that
 * emits `UserAction.ActionPressed("cancel")` to end the cycle thread.
 * Echoing them here would unmount the wrapper, replacing engine-level
 * cancel with `navigateBack()` (pop one nav-history step) — different
 * semantics.
 *
 * The function is the planned-deletion target of
 * `_private/docs/planning/todo/2026-05-27-corescreenidmap-rework-plan.md`
 * once the dispatch inversion + back-handler refactor lands; the echo
 * arms are the minimum-surface stopgap that closes the user-visible
 * bug without that larger rework.
 */
internal fun coreScreenIdToVariant(id: String): String? =
    when {
        // ── Allow-list: canonical `AppScreen::screen_id()` ids ──
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
        // These must appear **before** the `id.startsWith("contact_")`
        // / `id.startsWith("group")` echo arms below so the canonical
        // fold wins via `when` short-circuit.
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
        id.startsWith("duress_") -> "DuressPin"

        id == "backup" || id.startsWith("backup_") -> "Backup"

        id == "sync" || id.startsWith("sync_") -> "Sync"

        // ── Sub-screen echo arms (closes 2026-05-25-contact-tap-opens-own-card) ──
        // RHS = the input `id`; the dispatch reads only the
        // non-null-ness. Prefix groups follow the engine source layout
        // in core/vauchi-app/src/ui/ — adding a new sub-state to one
        // of these engines does not require an update here.
        id.startsWith("contact_") -> id

        // contact_detail, contact_visibility, contact_merge, contact_limit, contact_info, contact_not_found
        id.startsWith("group_") -> id

        // group_detail
        id == "my_info_entry_detail" -> id

        id.startsWith("edit_") -> id

        // edit_fields, edit_visibility, edit_preview (ContactEditEngine)
        id.startsWith("link_") -> id

        // device_linking.rs — 13 link_* sub-screens
        id.startsWith("replacement_") -> id

        // device_replacement.rs
        id.startsWith("recovery_") -> id

        // recovery sub-screens (RecoveryScreen wraps a CoreScreenView)
        id.startsWith("form_") -> id

        // form_dialog.rs — add/edit field/name/relay_url
        id.startsWith("shred_") -> id

        // emergency_shred.rs
        id in
            setOf(
                "social_graph",
                "delivery_status",
                "change_password",
                "privacy_settings",
                "activity_log",
                "fingerprint_verify",
                "default_name",
                "groups_setup",
                "what_next",
                "duplicate_detection",
                "lock_screen",
                "delete_identity_summary",
                "support",
                "identity_check",
                "link_choice",
                "deep_link_consent",
            ) -> id

        // `exchange_*` and `my_info` deliberately fall through to null
        // here: exchange_* renders through its native chrome wrappers;
        // my_info renders through ReadyScreen (Screen.Home arm). Both
        // own their dispatch via the local `Screen` enum, not via this
        // map.
        else -> null
    }
