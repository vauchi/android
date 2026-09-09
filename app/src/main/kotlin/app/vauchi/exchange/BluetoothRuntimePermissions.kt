// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.Manifest
import android.os.Build

/**
 * The Android runtime permissions a BLE command from Core needs before it
 * can execute.
 *
 * Core decides which transport a ritual uses and when it starts; it never
 * asks the shell for a permission by mode. The shell's only decision is
 * the OS-side one — which `Manifest.permission` constants the BLE
 * operation Core just commanded requires on this Android version
 * (ADR-066: execute a platform command, report its outcome).
 *
 * Requested at execution time, in `MainActivity`, because an ungranted
 * `BLUETOOTH_SCAN` makes `startScanning` fail with no visible prompt —
 * the device scans forever in silence.
 */
object BluetoothRuntimePermissions {
    /** [sdkInt] is injected so the version gate is unit-testable. */
    fun forSdk(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            // Pre-Android 12: BLUETOOTH/BLUETOOTH_ADMIN are install-time;
            // BLE *scanning* requires location at runtime.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
