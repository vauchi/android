// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE central (initiator) side of the vauchi exchange — slice S1: scanning.
 *
 * Executes core's `Command::BleStartScanning` / `BleStopScanning`. Discovered
 * peripherals advertising the vauchi service UUID are reported back via
 * [onDiscovered] → `Event::BleDeviceDiscovered`. Connect + GATT client land in
 * S2/S3 (see `_private/docs/problems/2026-06-06-android-ble-execution/`).
 *
 * The caller (Activity) owns the lifecycle and must hold the runtime Bluetooth
 * permissions (requested per-mode on selection); the [SuppressLint] on
 * `startScan`/`stopScan` documents that the gate lives at the call site.
 */
class BleCentral(
    private val context: Context,
) {
    private val scanner: BluetoothLeScanner?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        }

    private var scanCallback: ScanCallback? = null

    /**
     * Start scanning for peripherals advertising [serviceUuid]. Each result is
     * reported via [onDiscovered] (device address, RSSI, raw advertising bytes).
     * Returns an error string if the scan could not start, else `null`.
     */
    @SuppressLint("MissingPermission")
    fun startScanning(
        serviceUuid: String,
        onDiscovered: (id: String, rssi: Short, advData: ByteArray) -> Unit,
    ): String? {
        val s = scanner ?: return "BLE scanner unavailable (adapter off?)"
        stopScanning()

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
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val advData = result.scanRecord?.bytes ?: ByteArray(0)
                    onDiscovered(result.device.address, result.rssi.toShort(), advData)
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

    /** Stop an in-progress scan; safe to call when not scanning. */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            scanner?.stopScan(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "BleCentral"
    }
}
