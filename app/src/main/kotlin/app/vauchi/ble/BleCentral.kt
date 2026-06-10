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
    private var inflightOp: GattOp? = null
    private var discoveryWaitRetries = 0
    private var discoveryRetries = 0
    private val opTimeout = Runnable { onOpTimeout() }

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
                    // Core owns the role tiebreak (ADR-043): the peer advertises
                    // its token as a 16-bit service UUID alongside the 128-bit
                    // service UUID (see BleUuids.ADV_TOKEN_BYTES for why
                    // 16-bit). Pick the token UUID out of the scan record's
                    // service-UUID list and deliver its bytes to core as the
                    // discovery event's adv_data; core compares and emits
                    // BleConnect only for the winner — android no longer decides
                    // the role. Wait for a result that carries the token UUID (an
                    // early adv-only callback may not have it yet).
                    val peerToken =
                        result.scanRecord
                            ?.serviceUuids
                            ?.firstNotNullOfOrNull { BleUuids.serviceUuidToToken(it.uuid) }
                            ?: return
                    // Report each peer once (a continuous low-latency scan repeats
                    // the same device; without this core emits a BleConnect per
                    // result and churns the connection).
                    if (!discovered.add(address)) return
                    listener.onDeviceDiscovered(address, result.rssi.toShort(), peerToken)
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
        // Never issue a GATT op from inside a callback stack: the first
        // KeyOffer write is triggered from onDescriptorWrite -> onConnected,
        // and old stacks (Galaxy S7 / Android 8) silently drop a re-entrant
        // op. Post onto the op handler so every write/read leaves the GATT
        // callback thread first.
        opHandler.post { gatt?.let { executeOp(it, op) } }
    }

    @Suppress("DEPRECATION")
    private fun executeOp(
        g: BluetoothGatt,
        op: GattOp,
    ) {
        inflightOp = op
        val service = g.getService(BleUuids.uuid(BleUuids.SERVICE))
        val ch = service?.getCharacteristic(BleUuids.uuid(opUuid(op)))
        if (ch == null) {
            // The peer's GATT discovery hasn't surfaced this characteristic yet
            // (a slow iOS CBPeripheralManager populates it a beat after connect).
            // Wait on a SEPARATE budget — burning the write-retry budget here is
            // exactly what stalled iPhone↔Android: all 8 write retries were spent
            // waiting for the char, leaving none for the real write once it
            // appeared. Don't refresh() (it wipes the just-discovered table).
            Log.w(TAG, "GATT op on unknown characteristic ${opUuid(op)} (awaiting discovery)")
            if (discoveryWaitRetries < MAX_DISCOVERY_WAIT) {
                discoveryWaitRetries++
                opHandler.postDelayed({ gatt?.let { executeOp(it, op) } }, OP_RETRY_MS)
            } else {
                Log.w(TAG, "GATT characteristic ${opUuid(op)} never appeared; giving up")
                finishOp()
            }
            return
        }
        // Characteristic present — the discovery wait is over; the write keeps
        // its own full retry budget.
        discoveryWaitRetries = 0
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
            // Synchronous reject (writeCharacteristic returned false) — old
            // stacks (Galaxy S7 / Android 8) transiently reject right after
            // connection setup. Retry with backoff instead of dropping it.
            retryOrGiveUp(op, "rejected")
            return
        }
        // Accepted locally — but the S7 / Android-8 stack can still drop the
        // write on the wire with no onCharacteristicWrite ever firing, which
        // would stall the queue forever (opInFlight stuck true). Arm a
        // watchdog; the matching write/read callback cancels it via finishOp.
        opHandler.removeCallbacks(opTimeout)
        opHandler.postDelayed(opTimeout, OP_TIMEOUT_MS)
    }

    /**
     * Re-issue the current op with backoff, or finish it once the retry budget
     * is spent. Shared by the synchronous-reject and watchdog-timeout paths.
     */
    private fun retryOrGiveUp(
        op: GattOp,
        reason: String,
    ) {
        if (inflightRetries < MAX_OP_RETRIES) {
            inflightRetries++
            opHandler.postDelayed({ gatt?.let { executeOp(it, op) } }, OP_RETRY_MS)
        } else {
            Log.w(TAG, "GATT op gave up ($reason) after $inflightRetries retries: ${opUuid(op)}")
            finishOp()
        }
    }

    /**
     * No write/read callback arrived within OP_TIMEOUT_MS — the wire-level drop
     * the S7-as-central KeyOffer hit (write accepted locally, never delivered).
     * Re-issue rather than stall the handshake forever.
     */
    private fun onOpTimeout() {
        val op = synchronized(opLock) { if (!opInFlight) return else inflightOp } ?: return
        Log.w(TAG, "GATT op timeout (no callback) ${opUuid(op)} try=$inflightRetries")
        retryOrGiveUp(op, "timeout")
    }

    private fun finishOp() {
        opHandler.removeCallbacks(opTimeout)
        inflightRetries = 0
        discoveryWaitRetries = 0
        inflightOp = null
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
                val service = g.getService(BleUuids.uuid(BleUuids.SERVICE))
                // Android 8 can complete discovery with an INCOMPLETE
                // characteristic list. Signalling onConnected here would race
                // the KeyOffer write against a half-populated service — the
                // …894 "unknown characteristic" S7-as-central stall. Require the
                // handshake-write char before continuing; otherwise re-discover
                // until it appears (it does, just a few hundred ms late).
                val handshake =
                    service?.getCharacteristic(BleUuids.uuid(BleUuids.HANDSHAKE_WRITE))
                Log.d(TAG, "discovered ${service?.characteristics?.size ?: 0} chars (handshake=${handshake != null})")
                if (handshake == null) {
                    if (discoveryRetries < MAX_DISCOVERY_RETRIES) {
                        discoveryRetries++
                        opHandler.postDelayed({ gatt?.discoverServices() }, DISCOVERY_RETRY_MS)
                    } else {
                        listener.onDisconnected("incomplete service discovery (no handshake char)")
                    }
                    return
                }
                discoveryRetries = 0
                notifyQueue.clear()
                service.characteristics?.forEach { ch ->
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
        const val OP_RETRY_MS = 150L

        // Separate budget for waiting on a slow peer GATT discovery to surface
        // a characteristic (iOS CBPeripheralManager). ~4.5s, kept distinct from
        // the write-retry budget so the real write isn't starved.
        const val MAX_DISCOVERY_WAIT = 30

        // Re-discovery budget when the first discovery returns an incomplete
        // characteristic list (Android 8). ~6s total, since the S7 can take a
        // few seconds to surface the full table.
        const val MAX_DISCOVERY_RETRIES = 15
        const val DISCOVERY_RETRY_MS = 400L

        // Watchdog window: if no write/read callback arrives this long after a
        // GATT op was accepted locally (ok=true), assume the wire-level drop
        // the Galaxy S7 (Android 8) exhibits as central, and re-issue the op.
        // Generous enough that a real handshake write (tens of ms) never
        // false-trips it.
        const val OP_TIMEOUT_MS = 1500L

        // BluetoothDevice.TRANSPORT_LE inlined to avoid an extra import.
        const val TRANSPORT_LE = 2
    }
}
