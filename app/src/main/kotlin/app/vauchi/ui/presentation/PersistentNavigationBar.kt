// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

/**
 * Pure render model for the persistent navigation bar. Core sends an
 * empty item list to mean "no bar" (e.g. a locked app) rather than a
 * separate visibility flag, so [isVisible] is derived rather than carried.
 */
internal data class NavigationBarModel(
    val items: List<NavigationItem>,
) {
    val isVisible: Boolean
        get() = items.isNotEmpty()

    val selectedIndex: Int?
        get() = items.indexOfFirst { it.selected }.takeIf { it >= 0 }

    /**
     * The exchange destination is identified by its icon token, not its
     * position — Core places it centrally today, but the token stays the
     * stable contract if a future reorder moves it.
     */
    val centerItemIndex: Int?
        get() = items.indexOfFirst { it.iconToken == EXCHANGE_ICON_TOKEN }.takeIf { it >= 0 }

    val centerItem: NavigationItem?
        get() = centerItemIndex?.let(items::get)

    companion object {
        const val EXCHANGE_ICON_TOKEN = "qrcode"
    }
}
