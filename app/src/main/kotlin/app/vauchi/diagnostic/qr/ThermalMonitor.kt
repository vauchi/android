// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Monitors battery temperature to prevent thermal throttling during
 * camera sweep operations.
 */
class ThermalMonitor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "QRTuner"
        private const val CRITICAL_TEMP_C = 40.0f
        private const val SAFE_TEMP_C = 37.0f
        private const val COOLDOWN_POLL_MS = 5_000L
    }

    /**
     * Returns the current battery temperature in degrees Celsius.
     * The BatteryManager reports temperature in tenths of a degree.
     */
    fun getTemperatureCelsius(): Float {
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tempTenths < 0) {
            Log.w(TAG, "Could not read battery temperature")
            return 0.0f
        }
        return tempTenths / 10.0f
    }

    /**
     * Returns true if the battery temperature exceeds the critical threshold (40 C).
     */
    fun isCritical(): Boolean = getTemperatureCelsius() > CRITICAL_TEMP_C

    /**
     * Returns true if the battery temperature is at or below the safe-to-resume
     * threshold (37 C).
     */
    fun isSafeToResume(): Boolean = getTemperatureCelsius() <= SAFE_TEMP_C

    /**
     * Suspends until the temperature drops to a safe level.
     * Polls every 5 seconds.
     */
    suspend fun waitForCooldown() {
        while (!isSafeToResume()) {
            val temp = getTemperatureCelsius()
            Log.i(TAG, "Thermal cooldown: ${temp}C, waiting...")
            delay(COOLDOWN_POLL_MS)
        }
        Log.i(TAG, "Thermal cooldown complete: ${getTemperatureCelsius()}C")
    }
}
