// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE peripheral (responder) side of the vauchi exchange — slice S1:
 * advertising.
 *
 * Executes core's `Command::BleStartAdvertising` by advertising the vauchi
 * service UUID so the peer's central can discover us. The exchange [payload]
 * (174 bytes) does not fit a 31-byte advertisement; it is served later via the
 * `CHAR_EXCHANGE_PAYLOAD` GATT characteristic once a connection is up (S2/S3).
 * For S1 we advertise connectable so the discovery handshake works end to end.
 *
 * The GATT server (characteristics, connection callbacks) lands in S2. See
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
class BlePeripheral(
    private val context: Context,
) {
    private val advertiser: BluetoothLeAdvertiser?
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        }

    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Start advertising [serviceUuid] (connectable). [payload] is retained for
     * the S2/S3 GATT-server payload characteristic. Returns an error string if
     * advertising could not start, else `null`.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(
        serviceUuid: String,
        @Suppress("UNUSED_PARAMETER") payload: ByteArray,
    ): String? {
        val adv = advertiser ?: return "BLE advertiser unavailable (adapter off?)"
        stopAdvertising()

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
                .build()
        val cb =
            object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "BLE advertise failed: $errorCode")
                }
            }
        advertiseCallback = cb
        return try {
            adv.startAdvertising(settings, data, cb)
            null
        } catch (e: SecurityException) {
            advertiseCallback = null
            "Missing BLUETOOTH_ADVERTISE permission"
        }
    }

    /** Stop advertising; safe to call when not advertising. */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        try {
            advertiser?.stopAdvertising(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "BlePeripheral"
    }
}
