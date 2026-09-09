// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission strings and `Build.VERSION_CODES` are compile-time
 * constants, so these run on the plain JVM; `sdkInt` is injected to
 * exercise the version gate deterministically.
 */
class BluetoothRuntimePermissionsTest {
    @Test
    fun android_12_and_later_need_the_runtime_bluetooth_trio() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            BluetoothRuntimePermissions.forSdk(sdkInt = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun before_android_12_ble_scanning_needs_fine_location_instead() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BluetoothRuntimePermissions.forSdk(sdkInt = Build.VERSION_CODES.O),
        )
    }
}
