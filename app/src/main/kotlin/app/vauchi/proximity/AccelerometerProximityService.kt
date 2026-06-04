// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accelerometer capture for the TapHoverShake "shake" co-location signal.
 *
 * A Humble hardware adapter (ADR-031): it starts/stops on core's
 * `Command.AccelerometerStart` / `Command.AccelerometerStop` and streams each
 * reading back as raw milli-g values for the caller to wrap in
 * `MobileEvent.AccelerometerData`. All correlation and decision logic lives in
 * core (`MultiStageSession`); this service only samples the sensor.
 *
 * Mirrors [AudioProximityService] — the other multi-stage proximity sensor.
 */
class AccelerometerProximityService(
    private val context: Context,
) : SensorEventListener {
    companion object {
        /** Standard gravity (m/s^2) used to convert sensor readings to milli-g. */
        const val STANDARD_GRAVITY = 9.80665f

        @Volatile
        private var instance: AccelerometerProximityService? = null

        fun getInstance(context: Context): AccelerometerProximityService =
            instance ?: synchronized(this) {
                instance
                    ?: AccelerometerProximityService(context.applicationContext)
                        .also { instance = it }
            }

        /**
         * Convert one axis reading in m/s^2 to milli-g (1 g =
         * [STANDARD_GRAVITY] m/s^2). At rest a single axis reads ~1000 milli-g;
         * core's envelope quantizes the magnitude and clamps at 8 g.
         */
        fun axisToMilliG(metersPerSecondSquared: Float): Int = (metersPerSecondSquared / STANDARD_GRAVITY * 1000f).toInt()
    }

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val isListening = AtomicBoolean(false)

    @Volatile
    private var onReading: ((Long, Int, Int, Int) -> Unit)? = null

    /** True iff the device exposes an accelerometer. */
    fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
            accelerometer != null

    /**
     * Begin streaming accelerometer readings. Each reading invokes [onReading]
     * with `(timestampMs, xMilliG, yMilliG, zMilliG)`. Idempotent; a no-op when
     * the device has no accelerometer (core then times out the shake stage).
     */
    fun start(onReading: (Long, Int, Int, Int) -> Unit) {
        if (!isAvailable()) return
        if (!isListening.compareAndSet(false, true)) return
        this.onReading = onReading
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Stop streaming. Idempotent. */
    fun stop() {
        if (!isListening.compareAndSet(true, false)) return
        sensorManager?.unregisterListener(this)
        onReading = null
    }

    fun isActive(): Boolean = isListening.get()

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val callback = onReading ?: return
        // SensorEvent.values are m/s^2 including gravity; convert each axis to
        // milli-g to match core's envelope shape.
        val xMilliG = axisToMilliG(event.values[0])
        val yMilliG = axisToMilliG(event.values[1])
        val zMilliG = axisToMilliG(event.values[2])
        // event.timestamp is nanoseconds since boot — core needs only a
        // monotonic millisecond stamp for ordering, not wall-clock time.
        val timestampMs = event.timestamp / 1_000_000L
        callback(timestampMs, xMilliG, yMilliG, zMilliG)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {
        // No-op: accuracy changes don't affect magnitude correlation.
    }
}
