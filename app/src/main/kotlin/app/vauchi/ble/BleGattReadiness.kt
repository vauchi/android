// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

object BleGattReadiness {
    private const val ENABLE_NOTIFICATIONS: Byte = 1

    /**
     * A central enabling notifications on [BleUuids.DATA_NOTIFY] can receive
     * frames from then on; a notification sent earlier is lost to the 5 s
     * retry of Core's sender.
     */
    fun isDataSubscription(
        characteristicUuid: String,
        descriptorUuid: String,
        value: ByteArray,
    ): Boolean =
        characteristicUuid.equals(BleUuids.DATA_NOTIFY, ignoreCase = true) &&
            descriptorUuid.equals(BleUuids.CCC_DESCRIPTOR, ignoreCase = true) &&
            value.firstOrNull() == ENABLE_NOTIFICATIONS
}
