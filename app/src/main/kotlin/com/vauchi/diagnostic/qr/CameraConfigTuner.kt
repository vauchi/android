// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlinx.coroutines.delay
import uniffi.vauchi_mobile.MobileCameraConfig
import uniffi.vauchi_mobile.MobileErrorCorrectionLevel
import uniffi.vauchi_mobile.MobileQrConfig
import uniffi.vauchi_mobile.MobileTuningResult

/**
 * Result of decoding a single camera frame.
 */
data class FrameResult(
    val decoded: Boolean,
    val latencyMs: Float,
    val timestampNs: Long,
)

/**
 * Aggregate result of running one camera configuration through a frame sequence.
 */
data class ConfigRunResult(
    val configId: UInt,
    val framesTotal: UInt,
    val framesDecoded: UInt,
    val avgLatencyMs: Float,
    val jitterMs: Float,
    val thermalEvents: UInt,
    val latencies: List<Float>,
)

/**
 * Applies camera configurations via Camera2 interop and captures frame
 * decode results for scoring.
 */
class CameraConfigTuner(
    private val thermalMonitor: ThermalMonitor,
) {
    companion object {
        private const val TAG = "QRTuner"
        private const val STABILISATION_DELAY_MS = 1_500L
        private const val FRAMES_PER_CONFIG = 60
        private const val THERMAL_CHECK_INTERVAL = 20
    }

    /**
     * Applies camera settings from a [MobileCameraConfig] to the given [Camera]
     * using Camera2 interop.
     */
    @ExperimentalCamera2Interop
    fun applyConfig(
        camera: Camera,
        config: MobileCameraConfig,
    ) {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val builder = CaptureRequestOptions.Builder()

        config.iso?.let { iso ->
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso,
            )
        }

        config.exposureEv?.let { ev ->
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                ev,
            )
        }

        val awbMode = mapAwbModeToInt(config.whiteBalance)
        if (awbMode != null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                awbMode,
            )
        }

        camera2Control.captureRequestOptions = builder.build()
        Log.d(TAG, "Applied config ${config.id}: iso=${config.iso}, ev=${config.exposureEv}, awb=${config.whiteBalance}")
    }

    /**
     * Runs a single camera configuration: waits for stabilisation, then captures
     * [FRAMES_PER_CONFIG] frames, checking thermal state every [THERMAL_CHECK_INTERVAL] frames.
     *
     * @param configId The ID of the camera config being tested.
     * @param decodeFrame A suspend function that captures and decodes one frame.
     * @return Aggregated results for this configuration run.
     */
    suspend fun runConfig(
        configId: UInt,
        decodeFrame: suspend () -> FrameResult,
    ): ConfigRunResult {
        Log.d(TAG, "Starting config $configId, stabilisation delay ${STABILISATION_DELAY_MS}ms")
        delay(STABILISATION_DELAY_MS)

        val latencies = mutableListOf<Float>()
        var framesDecoded = 0u
        var thermalEvents = 0u

        for (i in 0 until FRAMES_PER_CONFIG) {
            if (i > 0 && i % THERMAL_CHECK_INTERVAL == 0) {
                if (thermalMonitor.isCritical()) {
                    thermalEvents++
                    Log.w(TAG, "Thermal event at frame $i of config $configId")
                    thermalMonitor.waitForCooldown()
                }
            }

            val result = decodeFrame()
            latencies.add(result.latencyMs)
            if (result.decoded) {
                framesDecoded++
            }
        }

        val avgLatency = if (latencies.isNotEmpty()) latencies.average().toFloat() else 0f
        val jitter =
            if (latencies.size > 1) {
                val mean = latencies.average()
                val variance = latencies.map { (it - mean) * (it - mean) }.average()
                kotlin.math.sqrt(variance).toFloat()
            } else {
                0f
            }

        Log.d(TAG, "Config $configId done: decoded=$framesDecoded/$FRAMES_PER_CONFIG, avg=${avgLatency}ms, jitter=${jitter}ms")

        return ConfigRunResult(
            configId = configId,
            framesTotal = FRAMES_PER_CONFIG.toUInt(),
            framesDecoded = framesDecoded,
            avgLatencyMs = avgLatency,
            jitterMs = jitter,
            thermalEvents = thermalEvents,
            latencies = latencies,
        )
    }

    /**
     * Converts a [ConfigRunResult] into a [MobileTuningResult] for scoring.
     */
    fun toTuningResult(
        run: ConfigRunResult,
        qrConfig: MobileQrConfig,
        actualIso: Int?,
        actualExposureEv: Int?,
    ): MobileTuningResult =
        MobileTuningResult(
            cameraConfigId = run.configId,
            qrErrorCorrection = qrConfig.errorCorrection,
            qrPayloadSizeBytes = qrConfig.payloadSizeBytes,
            qrModuleSizePx = qrConfig.moduleSizePx,
            decodeRate =
                if (run.framesTotal > 0u) {
                    run.framesDecoded.toFloat() / run.framesTotal.toFloat()
                } else {
                    0f
                },
            avgLatencyMs = run.avgLatencyMs,
            jitterMs = run.jitterMs,
            thermalEvents = run.thermalEvents,
            framesTotal = run.framesTotal,
            framesDecoded = run.framesDecoded,
            actualIso = actualIso,
            actualExposureEv = actualExposureEv,
        )

    private fun mapAwbModeToInt(mode: String): Int? =
        when (mode.uppercase()) {
            "OFF" -> CaptureRequest.CONTROL_AWB_MODE_OFF
            "AUTO" -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            "INCANDESCENT" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "FLUORESCENT" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            "WARM_FLUORESCENT" -> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
            "DAYLIGHT" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "CLOUDY_DAYLIGHT" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "TWILIGHT" -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
            "SHADE" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            else -> null
        }
}
