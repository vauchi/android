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
 * BLE central (initiator) — scanning (S1), connect/GATT client (S2), and
 * characteristic write/read (S3).
 *
 * GATT allows only one outstanding operation at a time, so writes and reads are
 * serialized through [opQueue] (each drained when the matching callback fires).
 * The caller (Activity) owns lifecycle + the runtime Bluetooth permissions. See
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
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
    private val discovered = mutableSetOf<String>()

    /**
     * Our own role-tiebreak token (set by the Activity from the advertise
     * payload). On discovery we connect only if our token is smaller than the
     * peer's; otherwise we stay responder (peripheral) and let the peer's
     * central connect to us. Avoids a symmetric double-connect. Null until
     * advertising is set up — then we report unconditionally (legacy fallback).
     */
    var ourToken: ByteArray? = null
    private var gatt: BluetoothGatt? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private sealed interface GattOp {
        data class Write(
            val uuid: String,
            val data: ByteArray,
        ) : GattOp

        data class Read(
            val uuid: String,
        ) : GattOp
    }

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false
    private val opHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var inflightRetries = 0

    // ── Scanning (S1) ────────────────────────────────────────────────────────

    fun startScanning(serviceUuid: String): String? {
        val s = scanner ?: return "BLE scanner unavailable (adapter off?)"
        stopScanning()
        discovered.clear()
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
                    val address = result.device.address
                    val peerToken =
                        result.scanRecord?.getServiceData(
                            ParcelUuid(UUID.fromString(BleUuids.SERVICE_DATA_UUID)),
                        )
                    val ours = ourToken
                    // Need both tokens to decide the role; an early adv-only
                    // result (no scan response yet) carries no peer token — wait
                    // for a token-bearing one.
                    if (ours != null && peerToken == null) return
                    // Report each peer once (a continuous low-latency scan repeats
                    // the same device; without this core emits a BleConnect per
                    // result and churns the connection).
                    if (!discovered.add(address)) return
                    if (ours != null && peerToken != null &&
                        BleUuids.compareTokens(ours, peerToken) >= 0
                    ) {
                        // We lose the tiebreak — stay responder (peripheral). Stop
                        // scanning; the peer's central connects to our GATT server.
                        Log.d(TAG, "BLE role: responder (peer initiates)")
                        stopScanning()
                        return
                    }
                    val advData = result.scanRecord?.bytes ?: ByteArray(0)
                    listener.onDeviceDiscovered(address, result.rssi.toShort(), advData)
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
            gatt = device.connectGatt(context, false, gattCallback, TRANSPORT_LE)
            null
        } catch (e: SecurityException) {
            "Missing BLUETOOTH_CONNECT permission"
        }
    }

    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        notifyQueue.clear()
        synchronized(opLock) {
            opQueue.clear()
            opInFlight = false
        }
        try {
            g.disconnect()
            g.close()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: ${e.javaClass.simpleName}")
        }
    }

    // ── Characteristic write / read (S3) ─────────────────────────────────────

    /** Queue a GATT write to [uuid] (initiator → responder). */
    fun writeCharacteristic(
        uuid: String,
        data: ByteArray,
    ) {
        enqueue(GattOp.Write(uuid, data))
    }

    /** Queue a GATT read of [uuid]; result arrives via onCharacteristicRead. */
    fun readCharacteristic(uuid: String) {
        enqueue(GattOp.Read(uuid))
    }

    private fun enqueue(op: GattOp) {
        synchronized(opLock) { opQueue.add(op) }
        processNextOp()
    }

    private fun processNextOp() {
        val g = gatt ?: return
        val op =
            synchronized(opLock) {
                if (opInFlight) return
                val next = opQueue.poll() ?: return
                opInFlight = true
                next
            }
        inflightRetries = 0
        executeOp(g, op)
    }

    @Suppress("DEPRECATION")
    private fun executeOp(
        g: BluetoothGatt,
        op: GattOp,
    ) {
        val service = g.getService(BleUuids.uuid(BleUuids.SERVICE))
        val ch = service?.getCharacteristic(BleUuids.uuid(opUuid(op)))
        if (ch == null) {
            Log.w(TAG, "GATT op on unknown characteristic ${opUuid(op)}")
            finishOp()
            return
        }
        val ok =
            when (op) {
                is GattOp.Write -> {
                    ch.writeType =
                        if (op.uuid in BleUuids.writeWithResponse) {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        }
                    ch.value = op.data
                    g.writeCharacteristic(ch)
                }

                is GattOp.Read -> {
                    g.readCharacteristic(ch)
                }
            }
        val kind = if (op is GattOp.Write) "write" else "read"
        Log.d(TAG, "GATT $kind ${opUuid(op)} ok=$ok try=$inflightRetries")
        if (!ok) {
            // Old GATT stacks (Galaxy S7 / Android 8) transiently reject an op
            // right after connection setup (writeCharacteristic returns false).
            // Retry with backoff instead of dropping it — previously the S7-as-
            // central KeyOffer was silently lost here.
            if (inflightRetries < MAX_OP_RETRIES) {
                inflightRetries++
                opHandler.postDelayed({ gatt?.let { executeOp(it, op) } }, OP_RETRY_MS)
            } else {
                Log.w(TAG, "GATT op failed after $inflightRetries retries: ${opUuid(op)}")
                finishOp()
            }
        }
    }

    private fun finishOp() {
        inflightRetries = 0
        synchronized(opLock) { opInFlight = false }
        processNextOp()
    }

    private fun opUuid(op: GattOp): String =
        when (op) {
            is GattOp.Write -> op.uuid
            is GattOp.Read -> op.uuid
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
                finishOp()
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                Log.d(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
                finishOp()
            }
        }

    @Suppress("DEPRECATION")
    private fun enableNextNotification(g: BluetoothGatt) {
        val ch = notifyQueue.poll()
        if (ch == null) {
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
        const val MAX_OP_RETRIES = 8
        const val OP_RETRY_MS = 60L

        // BluetoothDevice.TRANSPORT_LE inlined to avoid an extra import.
        const val TRANSPORT_LE = 2
    }
}
