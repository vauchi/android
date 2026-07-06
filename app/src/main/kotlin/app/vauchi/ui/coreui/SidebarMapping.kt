// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

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
// TODO(HUMBLE): W, P2. Maintains a domain-aware SF-Symbol→MaterialIcon name
// catalog in the view layer. Fix: core emits icon_token. (see _private
// problem record 2026-07-06-mobile-domain-shell-violations)
fun materialIconNameForCoreIcon(coreIcon: String): MaterialIconName =
    when (coreIcon) {
        "person.crop.rectangle" -> MaterialIconName.PERSON
        "person.2" -> MaterialIconName.PEOPLE
        "qrcode" -> MaterialIconName.QR_CODE
        "folder" -> MaterialIconName.GROUP
        "ellipsis.circle" -> MaterialIconName.MORE_HORIZ
        else -> MaterialIconName.MORE_HORIZ
    }

// `screenForCoreTabId` and `coreTabIdForScreen` removed in the
// 2026-04-30 Activity-enum-collapse Phase 1. Top-level navigation is
// now driven by core: `currentTabId(MOBILE)` for nav-bar visibility +
// pill selection, and `UserAction.NavigateToTab(tab.actionId)` for tab
// taps (ADR-043 Am4 Tier-1).
