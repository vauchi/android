// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

/**
 * Core's canonical `Event::PermissionGranted` for a prompt the shell raised
 * itself. The generated `MobileEvent` has no such case, so the grant goes as
 * event JSON (vauchi/private#1022).
 */
object PermissionGrantEvent {
    private val plainToken = Regex("[a-z_]+")

    fun json(transport: String): String {
        require(plainToken.matches(transport)) { "transport must be a plain token" }
        return """{"PermissionGranted":{"transport":"$transport"}}"""
    }

    /** The Bluetooth prompt resolved with every requested permission granted. */
    fun bleGranted(grants: Map<String, Boolean>): Boolean = grants.isNotEmpty() && grants.values.all { it }
}
