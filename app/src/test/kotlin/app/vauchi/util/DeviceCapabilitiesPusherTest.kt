// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the JSON wire shape that [buildDeviceCapabilitiesJson] sends to
 * core's `setDeviceCapabilitiesJson`. The string must deserialize into
 * core's `DeviceCapabilities` (serde, snake_case keys, bare-string enum
 * variants) with `platform` = `"Android"`. A silent key/casing drift
 * here would re-introduce the all-false `DeviceCapabilities::default()`
 * bug that `2026-05-23-exchange-capabilities-frontend-gap` was filed
 * for.
 *
 * Hardware detection ([detectDeviceHardware]) is not unit-tested: it
 * reads `PackageManager` / `BiometricManager` and has no deterministic
 * output. The contract that matters — the wire shape — lives in the
 * pure [buildDeviceCapabilitiesJson] builder, fully covered here.
 *
 * Uses Robolectric only for `org.json.JSONObject` (Android's bundled
 * JSON, stubbed on the plain JVM classpath).
 */
@RunWith(RobolectricTestRunner::class)
class DeviceCapabilitiesPusherTest {
    @Test
    fun `full capability phone serializes every field as Android`() {
        val hardware =
            DeviceHardware(
                hasNfc = true,
                hasBle = true,
                hasCamera = true,
                audio = DeviceAudioCapability.FULL,
                hasBiometrics = true,
                biometricType = DeviceBiometricType.FINGERPRINT,
                hasSecureEnclave = true,
                hasAccelerometer = true,
                hasInternet = true,
                hasUsbPort = false,
            )

        val json = JSONObject(buildDeviceCapabilitiesJson(hardware))

        assertTrue(json.getBoolean("has_nfc"))
        assertTrue(json.getBoolean("has_ble"))
        assertTrue(json.getBoolean("has_camera"))
        assertEquals("Full", json.getString("audio"))
        assertTrue(json.getBoolean("has_biometrics"))
        assertEquals("Fingerprint", json.getString("biometric_type"))
        assertTrue(json.getBoolean("has_secure_enclave"))
        assertEquals("Android", json.getString("platform"))
        assertTrue(json.getBoolean("has_accelerometer"))
        assertTrue(json.getBoolean("has_internet"))
        assertFalse(json.getBoolean("has_usb_port"))
    }

    @Test
    fun `face unlock device maps to FaceId`() {
        val hardware =
            DeviceHardware(
                hasNfc = false,
                hasBle = true,
                hasCamera = true,
                audio = DeviceAudioCapability.FULL,
                hasBiometrics = true,
                biometricType = DeviceBiometricType.FACE_ID,
                hasSecureEnclave = true,
                hasAccelerometer = true,
                hasInternet = true,
                hasUsbPort = false,
            )

        val json = JSONObject(buildDeviceCapabilitiesJson(hardware))

        assertEquals("FaceId", json.getString("biometric_type"))
        assertFalse(json.getBoolean("has_nfc"))
    }

    @Test
    fun `no biometrics emits JSON null biometric_type`() {
        val hardware =
            DeviceHardware(
                hasNfc = false,
                hasBle = true,
                hasCamera = false,
                audio = DeviceAudioCapability.EMIT_ONLY,
                hasBiometrics = false,
                biometricType = null,
                hasSecureEnclave = false,
                hasAccelerometer = false,
                hasInternet = true,
                hasUsbPort = false,
            )

        val raw = buildDeviceCapabilitiesJson(hardware)
        val json = JSONObject(raw)

        assertTrue(
            "nil biometricType must serialize as JSON null, got: $raw",
            raw.contains("\"biometric_type\":null"),
        )
        // serde's `Option<BiometricType>` accepts JSON null for None.
        assertTrue(json.isNull("biometric_type"))
        assertFalse(json.getBoolean("has_biometrics"))
        assertEquals("EmitOnly", json.getString("audio"))
        assertFalse(json.getBoolean("has_camera"))
        assertEquals("Android", json.getString("platform"))
    }

    @Test
    fun `audio capability wire values match core variants`() {
        assertEquals("Full", DeviceAudioCapability.FULL.wire)
        assertEquals("EmitOnly", DeviceAudioCapability.EMIT_ONLY.wire)
        assertEquals("ReceiveOnly", DeviceAudioCapability.RECEIVE_ONLY.wire)
        assertEquals("None", DeviceAudioCapability.NONE.wire)
    }

    @Test
    fun `biometric type wire values match core variants`() {
        assertEquals("Fingerprint", DeviceBiometricType.FINGERPRINT.wire)
        assertEquals("FaceId", DeviceBiometricType.FACE_ID.wire)
        assertEquals("Iris", DeviceBiometricType.IRIS.wire)
    }
}
