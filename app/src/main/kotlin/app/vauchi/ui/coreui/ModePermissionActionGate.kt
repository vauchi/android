// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import app.vauchi.exchange.ExchangeModePermissions

/**
 * Holds a mode-selection action until Android finishes its permission flow.
 *
 * Dispatching the action before the result arrives lets core enter the ritual
 * and start Bluetooth while the OS dialog is still visible.
 */
internal class ModePermissionActionGate(
    private val permissionsForMode: (String) -> List<String> = ExchangeModePermissions::forMode,
) {
    private var pendingAction: UserAction.ListItemSelected? = null

    fun defer(action: UserAction.ListItemSelected): List<String> {
        val permissions = permissionsForMode(action.itemId)
        pendingAction = action.takeIf { permissions.isNotEmpty() }
        return permissions
    }

    fun resolve(allGranted: Boolean): UserAction.ListItemSelected? {
        val action = pendingAction
        pendingAction = null
        return action.takeIf { allGranted }
    }
}
