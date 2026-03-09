// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import uniffi.vauchi_platform.MobileBleDelegate
import uniffi.vauchi_platform.MobileBleExchangeResult
import uniffi.vauchi_platform.MobileBleState
import java.util.UUID

/**
 * Android implementation of the core BLE delegate interface.
 *
 * Core calls these methods to instruct the platform to perform BLE operations.
 * The Android app pushes events back to core via `MobileBleExchangeSession`.
 */
class AndroidBleDelegate(
    private val context: Context,
    private val gatt: BluetoothGatt?,
    private val gattServer: BluetoothGattServer?,
    private val onStateChanged: (MobileBleState) -> Unit,
    private val onComplete: (MobileBleExchangeResult) -> Unit,
    private val onFailed: (String) -> Unit,
) : MobileBleDelegate {
    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun sendData(
        characteristicUuid: String,
        data: ByteArray,
    ) {
        if (!hasBluetoothConnect()) {
            Log.e("Vauchi", "[BLE] BLUETOOTH_CONNECT not granted, cannot send data")
            return
        }
        val uuid = UUID.fromString(characteristicUuid)

        gatt?.let { client ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                client.services
                    ?.flatMap { it.characteristics }
                    ?.find { it.uuid == uuid }
                    ?.let { characteristic ->
                        characteristic.value = data
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        client.writeCharacteristic(characteristic)
                    }
            }
        }

        Log.i("Vauchi", "[BLE] Sent ${data.size} bytes to $characteristicUuid")
    }

    override fun subscribeNotify(characteristicUuid: String) {
        if (!hasBluetoothConnect()) {
            Log.e("Vauchi", "[BLE] BLUETOOTH_CONNECT not granted, cannot subscribe")
            return
        }
        val uuid = UUID.fromString(characteristicUuid)

        gatt?.let { client ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                client.services
                    ?.flatMap { it.characteristics }
                    ?.find { it.uuid == uuid }
                    ?.let { characteristic ->
                        client.setCharacteristicNotification(characteristic, true)
                    }
            }
        }
    }

    override fun disconnect() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            gatt?.disconnect()
        }
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
