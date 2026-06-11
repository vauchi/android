// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import uniffi.vauchi_platform.MobileEvent

/**
 * Maps a BLE start-failure message (the `String?` error returned by
 * [BleCentral] / [BlePeripheral] operations) onto the hardware event core
 * expects, so a denied runtime permission or a dead radio fails the
 * exchange flow visibly (FailedWithFallback) instead of leaving it on
 * "Searching…" forever
 * (`2026-06-11-exchange-waits-forever-without-capabilities`, Phase 0).
 */
object BleFailure {
    const val TRANSPORT = "ble"

    fun toEvent(error: String): MobileEvent =
        if (error.contains("permission", ignoreCase = true)) {
            MobileEvent.PermissionDenied(TRANSPORT)
        } else {
            MobileEvent.HardwareUnavailable(TRANSPORT)
        }
}
