// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import uniffi.vauchi_platform.MobileBleLinkDirection
import uniffi.vauchi_platform.MobileEvent
import java.util.UUID

/**
 * Android BLE exchange service for the ADR-031 command/event protocol.
 *
 * Uses the native Android BLE stack (BluetoothLeScanner, BluetoothGatt)
 * to execute BLE exchange commands and report results back via callback.
 */
@SuppressLint("MissingPermission") // Callers must check BLUETOOTH_CONNECT permission
class BleExchangeService(
    private val context: Context,
    private val eventCallback: (MobileEvent) -> Unit,
) {
    companion object {
        private const val TAG = "BleExchange"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val discoveredCharacteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var pendingWrite: Pair<String, ByteArray>? = null
    private var pendingRead: String? = null

    // ── Scanning ────────────────────────────────────────────────────

    fun startScanning(serviceUuid: String) {
        scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            eventCallback(MobileEvent.HardwareUnavailable("BLE"))
            return
        }

        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
                .build()
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                val device = result.device
                val rssi = result.rssi
                val advData = result.scanRecord?.bytes ?: byteArrayOf()

                eventCallback(
                    MobileEvent.BleDeviceDiscovered(
                        id = device.address,
                        rssi = rssi.toShort(),
                        advData = advData,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                eventCallback(
                    MobileEvent.HardwareError("BLE", "Scan failed: code $errorCode"),
                )
            }
        }

    // ── Connection ──────────────────────────────────────────────────

    fun connect(deviceId: String) {
        val device: BluetoothDevice =
            adapter?.getRemoteDevice(deviceId) ?: run {
                eventCallback(MobileEvent.HardwareError("BLE", "Device $deviceId not found"))
                return
            }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        discoveredCharacteristics.clear()
        pendingWrite = null
        pendingRead = null
    }

    // ── GATT Operations ─────────────────────────────────────────────

    fun writeCharacteristic(
        uuid: String,
        data: ByteArray,
    ) {
        val normalizedUuid = uuid.lowercase()
        val ch = discoveredCharacteristics[normalizedUuid]
        if (ch == null) {
            pendingWrite = Pair(normalizedUuid, data)
            return
        }
        ch.value = data
        gatt?.writeCharacteristic(ch)
    }

    fun readCharacteristic(uuid: String) {
        val normalizedUuid = uuid.lowercase()
        val ch = discoveredCharacteristics[normalizedUuid]
        if (ch == null) {
            pendingRead = normalizedUuid
            return
        }
        gatt?.readCharacteristic(ch)
    }

    // ── GATT Callback ───────────────────────────────────────────────

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // GATT client callback → we dialed out → central → Outbound.
                        eventCallback(
                            MobileEvent.BleConnected(
                                deviceId = gatt.device.address,
                                direction = MobileBleLinkDirection.OUTBOUND,
                            ),
                        )
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        eventCallback(
                            MobileEvent.BleDisconnected(
                                deviceId = gatt.device.address,
                                direction = MobileBleLinkDirection.OUTBOUND,
                                reason = "disconnected (status=$status)",
                            ),
                        )
                        discoveredCharacteristics.clear()
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    eventCallback(MobileEvent.HardwareError("BLE", "Service discovery failed: $status"))
                    return
                }

                // Cache all characteristics by UUID and subscribe to notifications
                for (service in gatt.services) {
                    for (ch in service.characteristics) {
                        val uuid = ch.uuid.toString().lowercase()
                        discoveredCharacteristics[uuid] = ch

                        // Enable notifications if supported
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                        ) {
                            gatt.setCharacteristicNotification(ch, true)
                            val descriptor = ch.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                }

                // Execute pending operations
                pendingWrite?.let { (uuid, data) ->
                    discoveredCharacteristics[uuid]?.let { ch ->
                        ch.value = data
                        gatt.writeCharacteristic(ch)
                    }
                    pendingWrite = null
                }
                pendingRead?.let { uuid ->
                    discoveredCharacteristics[uuid]?.let { ch ->
                        gatt.readCharacteristic(ch)
                    }
                    pendingRead = null
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val uuid = characteristic.uuid.toString().lowercase()
                    eventCallback(
                        MobileEvent.BleCharacteristicRead(
                            deviceId = gatt.device.address,
                            direction = MobileBleLinkDirection.OUTBOUND,
                            uuid = uuid,
                            data = value,
                        ),
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                val uuid = characteristic.uuid.toString().lowercase()
                eventCallback(
                    MobileEvent.BleCharacteristicNotified(
                        deviceId = gatt.device.address,
                        direction = MobileBleLinkDirection.OUTBOUND,
                        uuid = uuid,
                        data = value,
                    ),
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    eventCallback(
                        MobileEvent.HardwareError(
                            "BLE",
                            "Write failed: status=$status",
                        ),
                    )
                }
            }
        }
}
