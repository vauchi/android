// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleGattReadinessTest {
    private val enable = byteArrayOf(1, 0)
    private val disable = byteArrayOf(0, 0)

    private fun subscription(
        characteristic: String = BleUuids.DATA_NOTIFY,
        descriptor: String = BleUuids.CCC_DESCRIPTOR,
        value: ByteArray = enable,
    ) = BleGattReadiness.isDataSubscription(characteristic, descriptor, value)

    @Test
    fun enabling_data_notifications_is_ready() {
        assertEquals(true, subscription())
    }

    @Test
    fun the_platform_may_report_the_uuid_in_upper_case() {
        assertEquals(true, subscription(characteristic = BleUuids.DATA_NOTIFY.uppercase()))
    }

    @Test
    fun another_characteristic_is_not_ready() {
        assertEquals(false, subscription(characteristic = BleUuids.HANDSHAKE_NOTIFY))
    }

    @Test
    fun another_descriptor_is_not_ready() {
        assertEquals(false, subscription(descriptor = "00002901-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun disabling_notifications_is_not_ready() {
        assertEquals(false, subscription(value = disable))
    }

    @Test
    fun an_empty_write_is_not_ready() {
        assertEquals(false, subscription(value = byteArrayOf()))
    }
}
