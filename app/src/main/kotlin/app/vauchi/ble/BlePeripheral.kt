// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque

/**
 * Receives BLE peripheral-side events; the Activity forwards these to
 * `CoreAppViewModel.onBle*`.
 */
interface BlePeripheralListener {
    fun onConnected(deviceId: String)

    fun onDisconnected(reason: String)

    /** A connected central wrote to one of our characteristics (received data). */
    fun onCharacteristicReceived(
        uuid: String,
        data: ByteArray,
    )
}

/**
 * BLE peripheral (responder) — advertising (S1), GATT server (S2), and
 * notifying the central (S3).
 *
 * Notifications are serialized through [notifyQueue] (one in flight, drained on
 * `onNotificationSent`) since chunked card data fires many in a row. Assumes a
 * single connected central (the exchange peer). See
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
@SuppressLint("MissingPermission")
class BlePeripheral(
    private val context: Context,
    private val listener: BlePeripheralListener,
) {
    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.takeIf { it.isEnabled }

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var payload: ByteArray = ByteArray(0)
    private var pendingAdvSettings: AdvertiseSettings? = null
    private var pendingAdvData: AdvertiseData? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()

    private data class PendingNotify(
        val uuid: String,
        val data: ByteArray,
    )

    private val notifyLock = Any()
    private val notifyQueue = ArrayDeque<PendingNotify>()
    private var notifyInFlight = false

    fun startAdvertising(
        serviceUuid: String,
        payload: ByteArray,
    ): String? {
        val adapter = adapter() ?: return "BLE adapter off"
        this.payload = payload
        stopAdvertising()

        val adv = adapter.bluetoothLeAdvertiser ?: return "BLE advertiser unavailable"
        advertiser = adv
        pendingAdvSettings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        // Primary advertisement carries the 128-bit service UUID (scan filter
        // + GATT) plus the role-tiebreak token encoded as a 16-bit service UUID
        // (ADR-043; 16-bit because pre-Android-9 stacks truncate 32-bit UUIDs —
        // see BleUuids.ADV_TOKEN_BYTES). Both go in the primary advert — not
        // the scan response — so iOS, which can't advertise service data and
        // can't control its scan response, conveys the token the same way.
        // 25 bytes ≤ the 31-byte advert. The peer's central reads it back via
        // serviceUuidToToken.
        pendingAdvData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleUuids.uuid(serviceUuid)))
                .addServiceUuid(ParcelUuid(BleUuids.tokenToServiceUuid(payload)))
                .build()
        // Open the GATT server + add the service FIRST; advertising starts in
        // onServiceAdded, never before. addService() is async — if we advertise
        // immediately a fast central (Galaxy S7, LOW_LATENCY) connects and
        // discovers during the addService window and never finds the handshake
        // characteristic (…894), stalling the S7-as-central exchange.
        openGattServer(serviceUuid)
        return null
    }

    /** Start advertising once the GATT service is registered (onServiceAdded). */
    private fun beginAdvertising() {
        val adv = advertiser ?: return
        val settings = pendingAdvSettings ?: return
        val data = pendingAdvData ?: return
        val cb =
            object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "BLE advertise failed: $errorCode")
                }
            }
        advertiseCallback = cb
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) {
            advertiseCallback = null
            Log.e(TAG, "BLE advertise: missing BLUETOOTH_ADVERTISE permission")
        }
    }

    fun stopAdvertising() {
        advertiseCallback?.let { cb ->
            advertiseCallback = null
            try {
                advertiser?.stopAdvertising(cb)
            } catch (e: Exception) {
                Log.w(TAG, "stopAdvertising: ${e.javaClass.simpleName}")
            }
        }
        subscribers.clear()
        synchronized(notifyLock) {
            notifyQueue.clear()
            notifyInFlight = false
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gattServer close: ${e.javaClass.simpleName}")
        }
        gattServer = null
        pendingAdvSettings = null
        pendingAdvData = null
    }

    private fun openGattServer(serviceUuid: String) {
        val manager = bluetoothManager ?: return
        val server = manager.openGattServer(context, gattServerCallback) ?: return
        gattServer = server

        val service =
            BluetoothGattService(
                BleUuids.uuid(serviceUuid),
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        BleUuids.allCharacteristics.forEach { uuid ->
            service.addCharacteristic(buildCharacteristic(uuid))
        }
        server.addService(service)
    }

    private fun buildCharacteristic(uuid: String): BluetoothGattCharacteristic {
        var props = 0
        var perms = 0
        if (uuid == BleUuids.EXCHANGE_PAYLOAD) {
            props = props or BluetoothGattCharacteristic.PROPERTY_READ
            perms = perms or BluetoothGattCharacteristic.PERMISSION_READ
        }
        if (uuid in BleUuids.writeWithResponse) {
            props = props or BluetoothGattCharacteristic.PROPERTY_WRITE
            perms = perms or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (uuid == BleUuids.DATA_WRITE) {
            props = props or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            perms = perms or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (uuid in BleUuids.notifyCharacteristics) {
            props = props or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            perms = perms or BluetoothGattCharacteristic.PERMISSION_READ
        }
        val ch = BluetoothGattCharacteristic(BleUuids.uuid(uuid), props, perms)
        if (uuid in BleUuids.notifyCharacteristics) {
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.uuid(BleUuids.CCC_DESCRIPTOR),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        return ch
    }

    /** Notify the connected central on [uuid] with [data] (peripheral → central). */
    fun notify(
        uuid: String,
        data: ByteArray,
    ) {
        synchronized(notifyLock) { notifyQueue.add(PendingNotify(uuid, data)) }
        processNextNotify()
    }

    @Suppress("DEPRECATION")
    private fun processNextNotify() {
        val server = gattServer ?: return
        val pending =
            synchronized(notifyLock) {
                if (notifyInFlight) return
                val next = notifyQueue.poll() ?: return
                notifyInFlight = true
                next
            }
        val service = server.getService(BleUuids.uuid(BleUuids.SERVICE))
        val ch = service?.getCharacteristic(BleUuids.uuid(pending.uuid))
        val device = subscribers.firstOrNull()
        if (ch == null || device == null) {
            finishNotify()
            return
        }
        ch.value = pending.data
        if (!server.notifyCharacteristicChanged(device, ch, false)) finishNotify()
    }

    private fun finishNotify() {
        synchronized(notifyLock) { notifyInFlight = false }
        processNextNotify()
    }

    private val gattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "GATT service added — starting advertising")
                    beginAdvertising()
                } else {
                    Log.e(TAG, "GATT addService failed: $status")
                }
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        subscribers.add(device)
                        listener.onConnected(device.address)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        subscribers.remove(device)
                        listener.onDisconnected("central disconnected: $status")
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                listener.onCharacteristicReceived(characteristic.uuid.toString(), value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 0 /* GATT_SUCCESS */, offset, value)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val value =
                    if (characteristic.uuid == BleUuids.uuid(BleUuids.EXCHANGE_PAYLOAD)) payload else ByteArray(0)
                val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, 0 /* GATT_SUCCESS */, offset, slice)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 0 /* GATT_SUCCESS */, offset, value)
                }
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int,
            ) {
                finishNotify()
            }
        }

    private companion object {
        const val TAG = "BlePeripheral"
    }
}
