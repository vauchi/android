// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.biometric.BiometricManager
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * Ultrasonic audio capability — [wire] values match core's
 * `AudioCapability` serde variant names (`core/vauchi-core/src/types.rs`).
 */
internal enum class DeviceAudioCapability(
    val wire: String,
) {
    FULL("Full"),
    EMIT_ONLY("EmitOnly"),
    RECEIVE_ONLY("ReceiveOnly"),
    NONE("None"),
}

/**
 * Biometric hardware kind — [wire] values match core's `BiometricType`
 * serde variant names (`core/vauchi-core/src/exchange/capability/types.rs`).
 */
internal enum class DeviceBiometricType(
    val wire: String,
) {
    FINGERPRINT("Fingerprint"),
    FACE_ID("FaceId"),
    IRIS("Iris"),
}

/**
 * Plain value type holding the detected hardware flags. Separated from
 * detection so the JSON serialization is unit-testable without a
 * `Context` / Robolectric.
 */
internal data class DeviceHardware(
    val hasNfc: Boolean,
    val hasBle: Boolean,
    val hasCamera: Boolean,
    val audio: DeviceAudioCapability,
    val hasBiometrics: Boolean,
    val biometricType: DeviceBiometricType?,
    val hasSecureEnclave: Boolean,
    val hasAccelerometer: Boolean,
    val hasInternet: Boolean,
    val hasUsbPort: Boolean,
)

/**
 * Build the JSON object core's `DeviceCapabilities` deserializes
 * (`serde`, snake_case, every field `#[serde(default)]`). `platform`
 * is always `"Android"` from this pusher. Pure — no hardware access.
 */
internal fun buildDeviceCapabilitiesJson(hardware: DeviceHardware): String {
    val parts =
        buildList {
            add("\"has_nfc\":${hardware.hasNfc}")
            add("\"has_ble\":${hardware.hasBle}")
            add("\"has_camera\":${hardware.hasCamera}")
            add("\"audio\":\"${hardware.audio.wire}\"")
            add("\"has_biometrics\":${hardware.hasBiometrics}")
            val biometricType = hardware.biometricType
            if (biometricType != null) {
                add("\"biometric_type\":\"${biometricType.wire}\"")
            } else {
                add("\"biometric_type\":null")
            }
            add("\"has_secure_enclave\":${hardware.hasSecureEnclave}")
            add("\"platform\":\"Android\"")
            add("\"has_accelerometer\":${hardware.hasAccelerometer}")
            add("\"has_internet\":${hardware.hasInternet}")
            add("\"has_usb_port\":${hardware.hasUsbPort}")
        }
    return "{" + parts.joinToString(",") + "}"
}

/**
 * Map the device's biometric hardware features to core's biometric
 * kind. Reports the strongest commonly-used type present; gated by the
 * caller on [BiometricManager] actually being able to authenticate.
 * Unknown feature strings return `false` from [PackageManager] on older
 * devices, so this is safe down to minSdk.
 */
internal fun detectBiometricType(pm: PackageManager): DeviceBiometricType? =
    when {
        pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> DeviceBiometricType.FINGERPRINT
        pm.hasSystemFeature(PackageManager.FEATURE_FACE) -> DeviceBiometricType.FACE_ID
        pm.hasSystemFeature(PackageManager.FEATURE_IRIS) -> DeviceBiometricType.IRIS
        else -> null
    }

/**
 * Query Android hardware APIs for the exchange-relevant capabilities.
 *
 * Conservative choices:
 * - `audio` is `Full` when a microphone is present (speakers are
 *   universal on phones), else `EmitOnly`.
 * - `hasSecureEnclave` maps to StrongBox (`FEATURE_STRONGBOX_KEYSTORE`),
 *   the Android secure-element equivalent.
 * - `hasInternet` is `true` (capability, not current reachability).
 * - `hasUsbPort` is `false`: Cable exchange is not a supported mode, so
 *   we do not advertise it (matches iOS / macOS).
 */
internal fun detectDeviceHardware(context: Context): DeviceHardware {
    val pm = context.packageManager
    val canAuthenticate =
        BiometricManager
            .from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    val hasMicrophone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    return DeviceHardware(
        hasNfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC),
        hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        audio = if (hasMicrophone) DeviceAudioCapability.FULL else DeviceAudioCapability.EMIT_ONLY,
        hasBiometrics = canAuthenticate,
        biometricType = if (canAuthenticate) detectBiometricType(pm) else null,
        hasSecureEnclave = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        hasAccelerometer = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER),
        hasInternet = true,
        hasUsbPort = false,
    )
}

/**
 * Detect this device's hardware and push it to core's
 * `DeviceCapabilities`. Call once at boot, before the first navigation
 * to the Exchange screen. Idempotent at the core level — a later call
 * simply overwrites the stored capabilities.
 *
 * Closes the Android leg of `2026-05-23-exchange-capabilities-frontend-gap`:
 * without this push core falls back to `DeviceCapabilities::default()`
 * (all-false) and the Exchange mode picker offers nothing the device
 * can actually do.
 */
internal fun pushDeviceCapabilities(
    context: Context,
    engine: PlatformAppEngine?,
) {
    if (engine == null) return
    val json = buildDeviceCapabilitiesJson(detectDeviceHardware(context))
    try {
        engine.setDeviceCapabilitiesJson(json)
    } catch (e: Exception) {
        Log.e("Vauchi", "[DeviceCapabilitiesPusher] Failed: ${e.javaClass.simpleName}")
    }
}
