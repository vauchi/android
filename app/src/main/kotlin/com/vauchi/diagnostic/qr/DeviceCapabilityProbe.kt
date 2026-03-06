// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import uniffi.vauchi_mobile.MobileDeviceCapabilityProfile
import uniffi.vauchi_mobile.MobileFpsRange
import uniffi.vauchi_mobile.MobilePlatform

/**
 * Reads CameraCharacteristics for the front camera and builds a
 * [MobileDeviceCapabilityProfile] for the sweep matrix generator.
 */
object DeviceCapabilityProbe {
    private const val TAG = "QRTuner"

    fun probe(context: Context): MobileDeviceCapabilityProfile {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val frontCameraId = findFrontCamera(cameraManager)
        if (frontCameraId == null) {
            Log.w(TAG, "No front camera found, returning fallback profile")
            return fallbackProfile()
        }

        return try {
            buildProfile(cameraManager, frontCameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe camera capabilities", e)
            fallbackProfile()
        }
    }

    private fun findFrontCamera(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return null
    }

    private fun buildProfile(
        cameraManager: CameraManager,
        cameraId: String,
    ): MobileDeviceCapabilityProfile {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hwLevelName = mapHardwareLevel(hwLevel)

        val sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val isoMin = sensitivityRange?.lower
        val isoMax = sensitivityRange?.upper

        val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evMin = aeCompRange?.lower
        val evMax = aeCompRange?.upper

        val afModes =
            chars
                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.map { mapAfMode(it) } ?: emptyList()

        val awbModes =
            chars
                .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.map { mapAwbMode(it) } ?: emptyList()

        val fpsRanges =
            chars
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { MobileFpsRange(min = it.lower, max = it.upper) } ?: emptyList()

        val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
        val maxSize =
            outputSizes
                ?.filter { it.width <= 1920 && it.height <= 1440 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

        return MobileDeviceCapabilityProfile(
            platform = MobilePlatform.ANDROID,
            deviceModel = deviceModel,
            hardwareLevel = hwLevelName,
            isoRangeMin = isoMin,
            isoRangeMax = isoMax,
            exposureEvRangeMin = evMin,
            exposureEvRangeMax = evMax,
            afModes = afModes,
            awbModes = awbModes,
            fpsRanges = fpsRanges,
            maxResolutionWidth = (maxSize?.width ?: 1280).toUInt(),
            maxResolutionHeight = (maxSize?.height ?: 720).toUInt(),
        )
    }

    private fun fallbackProfile(): MobileDeviceCapabilityProfile {
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        return MobileDeviceCapabilityProfile(
            platform = MobilePlatform.ANDROID,
            deviceModel = deviceModel,
            hardwareLevel = null,
            isoRangeMin = null,
            isoRangeMax = null,
            exposureEvRangeMin = null,
            exposureEvRangeMax = null,
            afModes = emptyList(),
            awbModes = emptyList(),
            fpsRanges = emptyList(),
            maxResolutionWidth = 1280u,
            maxResolutionHeight = 720u,
        )
    }

    private fun mapHardwareLevel(level: Int?): String? =
        when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> null
        }

    private fun mapAfMode(mode: Int): String =
        when (mode) {
            CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
            CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
            CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
            CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
            else -> "UNKNOWN($mode)"
        }

    private fun mapAwbMode(mode: Int): String =
        when (mode) {
            CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
            CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
            CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
            else -> "UNKNOWN($mode)"
        }
}
