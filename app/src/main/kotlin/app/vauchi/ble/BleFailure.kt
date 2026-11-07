// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
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
    /** Upper case because Core 0.100.2's recovery link matches only `BLE`. */
    const val TRANSPORT = "BLE"

    fun toEvent(error: String): MobileEvent =
        if (error.contains("permission", ignoreCase = true)) {
            MobileEvent.PermissionDenied(TRANSPORT)
        } else {
            MobileEvent.HardwareUnavailable(TRANSPORT)
        }

    /** `null` when the radio is already doing what was asked: nothing failed. */
    fun advertiseStartFailure(errorCode: Int): String? =
        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
            null
        } else {
            "BLE advertise failed: $errorCode"
        }

    /** `null` when the radio is already doing what was asked: nothing failed. */
    fun scanStartFailure(errorCode: Int): String? =
        if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
            null
        } else {
            "BLE scan failed: $errorCode"
        }
}
