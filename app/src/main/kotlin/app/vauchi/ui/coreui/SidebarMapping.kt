// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import app.vauchi.Screen

/**
 * Pure-Kotlin enum identifying which Material Icon to render for a
 * `MobileTabInfo.icon` SF-Symbol name. Kept separate from the Compose
 * `ImageVector` lookup so the mapping can be unit-tested on the JVM
 * without pulling in `androidx.compose.material.icons` (which requires
 * an Android runtime).
 *
 * Resolved into a concrete `ImageVector` by `MainActivity` at render
 * time.
 */
enum class MaterialIconName {
    PERSON,
    PEOPLE,
    QR_CODE,
    GROUP,
    MORE_HORIZ,
}

/**
 * Map an SF-Symbol icon name from core's `MobileTabInfo` to the
 * Android Material Icon that should render in its place. Unknown
 * names fall back to `MORE_HORIZ` — a neutral-looking glyph that is
 * preferable to a runtime crash if core adds a new tab before the
 * Android binding picks up the icon.
 */
fun materialIconNameForCoreIcon(coreIcon: String): MaterialIconName =
    when (coreIcon) {
        "person.crop.rectangle" -> MaterialIconName.PERSON
        "person.2" -> MaterialIconName.PEOPLE
        "qrcode" -> MaterialIconName.QR_CODE
        "folder" -> MaterialIconName.GROUP
        "ellipsis.circle" -> MaterialIconName.MORE_HORIZ
        else -> MaterialIconName.MORE_HORIZ
    }

/**
 * Map a core `MobileTabInfo.id` (snake_case `screen_id` from
 * `AppScreen::screen_id()` in core) to the local `Screen` enum that
 * drives the Android navigation state machine.
 *
 * Only the five mobile top-level tabs are recognised — non-top-level
 * screens return `null` because they should not appear in the bottom
 * nav.
 */
fun screenForCoreTabId(id: String): Screen? =
    when (id) {
        "my_info" -> Screen.Home
        "contacts" -> Screen.Contacts
        "exchange" -> Screen.ExchangeModePicker
        "groups" -> Screen.Labels
        "more" -> Screen.More
        else -> null
    }

/**
 * Reverse of [screenForCoreTabId]. Returns the core tab id that the
 * given `Screen` belongs under for selection-state highlighting.
 *
 * The Exchange tab spans `ExchangeModePicker` plus its sub-modes
 * (`MultiStageExchange`, `NfcExchange`, `BleExchange`); all four fold
 * into `"exchange"` so the tab stays highlighted while the user is
 * inside any exchange flow. Screens that never appear in the bottom
 * nav return `null`.
 */
fun coreTabIdForScreen(screen: Screen): String? =
    when (screen) {
        Screen.Home -> "my_info"

        Screen.Contacts -> "contacts"

        Screen.ExchangeModePicker,
        Screen.MultiStageExchange,
        Screen.NfcExchange,
        Screen.BleExchange,
        -> "exchange"

        Screen.Labels -> "groups"

        Screen.More -> "more"

        else -> null
    }
