// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

internal object BleLinkAddress {
    fun matches(
        requested: String,
        actual: String,
    ): Boolean = requested.isEmpty() || requested == actual
}
