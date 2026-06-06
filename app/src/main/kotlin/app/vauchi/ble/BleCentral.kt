// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * Receives BLE central-side events; the Activity forwards these to
 * `CoreAppViewModel.onBle*` → `MobileEvent`.
 */
interface BleCentralListener {
    fun onDeviceDiscovered(
        id: String,
        rssi: Short,
        advData: ByteArray,
    )

    fun onConnected(deviceId: String)

    fun onDisconnected(reason: String)

    fun onCharacteristicNotified(
        uuid: String,
        data: ByteArray,
    )

    fun onCharacteristicRead(
        uuid: String,
        data: ByteArray,
    )
}

/**
 * BLE central (initiator) — scanning (S1) + connect/GATT client (S2).
 *
 * Executes `BleStartScanning` (scan, filtered on the vauchi service UUID) and
 * `BleConnect` (GATT connect → discover services → enable notifications →
 * `BleConnected`). Characteristic write/read data flow lands in S3. The caller
 * (Activity) owns lifecycle + the runtime Bluetooth permissions.
 *
 * See `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val listener: BleCentralListener,
) {
    private val scanner: BluetoothLeScanner?
        get() = adapter()?.bluetoothLeScanner

    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.takeIf { it.isEnabled }

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // ── Scanning (S1) ────────────────────────────────────────────────────────

    fun startScanning(serviceUuid: String): String? {
        val s = scanner ?: return "BLE scanner unavailable (adapter off?)"
        stopScanning()
        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
                .build()
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val advData = result.scanRecord?.bytes ?: ByteArray(0)
                    listener.onDeviceDiscovered(result.device.address, result.rssi.toShort(), advData)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed: $errorCode")
                }
            }
        scanCallback = cb
        return try {
            s.startScan(listOf(filter), settings, cb)
            null
        } catch (e: SecurityException) {
            scanCallback = null
            "Missing BLUETOOTH_SCAN permission"
        }
    }

    fun stopScanning() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            scanner?.stopScan(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.javaClass.simpleName}")
        }
    }

    // ── Connect / GATT client (S2) ───────────────────────────────────────────

    /**
     * Connect to [deviceId] (a BLE MAC address from a prior discovery). Stops
     * scanning first, then connects, requests a larger MTU, discovers services,
     * and enables notifications on every NOTIFY characteristic before reporting
     * [BleCentralListener.onConnected].
     */
    fun connect(deviceId: String): String? {
        val adapter = adapter() ?: return "BLE adapter off"
        stopScanning()
        disconnect()
        val device =
            try {
                adapter.getRemoteDevice(deviceId)
            } catch (e: IllegalArgumentException) {
                return "Invalid device id: $deviceId"
            }
        return try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice_TRANSPORT_LE)
            null
        } catch (e: SecurityException) {
            "Missing BLUETOOTH_CONNECT permission"
        }
    }

    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        notifyQueue.clear()
        try {
            g.disconnect()
            g.close()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: ${e.javaClass.simpleName}")
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Larger MTU first (best-effort); discovery happens in
                        // onMtuChanged so the negotiated size is in place.
                        if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        listener.onDisconnected("status=$status")
                        try {
                            g.close()
                        } catch (_: Exception) {
                        }
                        if (gatt === g) gatt = null
                    }
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onDisconnected("service discovery failed: $status")
                    return
                }
                notifyQueue.clear()
                val service = g.getService(BleUuids.uuid(BleUuids.SERVICE))
                service?.characteristics?.forEach { ch ->
                    val notifiable = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    if (notifiable) notifyQueue.add(ch)
                }
                enableNextNotification(g)
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                enableNextNotification(g)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                listener.onCharacteristicNotified(
                    characteristic.uuid.toString(),
                    characteristic.value ?: ByteArray(0),
                )
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onCharacteristicRead(
                        characteristic.uuid.toString(),
                        characteristic.value ?: ByteArray(0),
                    )
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun enableNextNotification(g: BluetoothGatt) {
        val ch = notifyQueue.poll()
        if (ch == null) {
            // All notify characteristics subscribed — the link is ready.
            listener.onConnected(g.device.address)
            return
        }
        g.setCharacteristicNotification(ch, true)
        val ccc = ch.getDescriptor(BleUuids.uuid(BleUuids.CCC_DESCRIPTOR))
        if (ccc == null) {
            enableNextNotification(g)
            return
        }
        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(ccc)) enableNextNotification(g)
    }

    private companion object {
        const val TAG = "BleCentral"
        const val REQUESTED_MTU = 247

        // BluetoothDevice.TRANSPORT_LE inlined to avoid an extra import.
        const val BluetoothDevice_TRANSPORT_LE = 2
    }
}
