// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
import uniffi.vauchi_mobile.MobileBleDelegate
import uniffi.vauchi_mobile.MobileBleExchangeResult
import uniffi.vauchi_mobile.MobileBleState
import java.util.UUID

/**
 * Android implementation of the core BLE delegate interface.
 *
 * Core calls these methods to instruct the platform to perform BLE operations.
 * The Android app pushes events back to core via `MobileBleExchangeSession`.
 */
class AndroidBleDelegate(
    private val gatt: BluetoothGatt?,
    private val gattServer: BluetoothGattServer?,
    private val onStateChanged: (MobileBleState) -> Unit,
    private val onComplete: (MobileBleExchangeResult) -> Unit,
    private val onFailed: (String) -> Unit,
) : MobileBleDelegate {
    override fun sendData(
        characteristicUuid: String,
        data: ByteArray,
    ) {
        val uuid = UUID.fromString(characteristicUuid)

        gatt?.let { client ->
            client.services
                ?.flatMap { it.characteristics }
                ?.find { it.uuid == uuid }
                ?.let { characteristic ->
                    characteristic.value = data
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    client.writeCharacteristic(characteristic)
                }
        }

        Log.i("Vauchi", "[BLE] Sent ${data.size} bytes to $characteristicUuid")
    }

    override fun subscribeNotify(characteristicUuid: String) {
        val uuid = UUID.fromString(characteristicUuid)

        gatt?.let { client ->
            client.services
                ?.flatMap { it.characteristics }
                ?.find { it.uuid == uuid }
                ?.let { characteristic ->
                    client.setCharacteristicNotification(characteristic, true)
                }
        }
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun onStateChanged(state: MobileBleState) {
        onStateChanged.invoke(state)
    }

    override fun onExchangeComplete(result: MobileBleExchangeResult) {
        onComplete.invoke(result)
    }

    override fun onExchangeFailed(error: String) {
        Log.e("Vauchi", "[BLE] Exchange failed: $error")
        onFailed.invoke(error)
    }
}
