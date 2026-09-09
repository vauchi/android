// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.JsonPrimitive
import uniffi.vauchi_platform.MobileBleLinkDirection
import uniffi.vauchi_platform.MobileEvent

/**
 * Encode a typed hardware observation as the canonical event JSON Core
 * decodes in `dispatchJson` (ADR-066: one Event input for every shell
 * observation). The spelling mirrors Core's serde contract: an externally
 * tagged variant, snake_case fields, bytes as unsigned integer arrays.
 */
fun MobileEvent.toEventJson(): String =
    when (this) {
        is MobileEvent.QrScanned -> {
            variant("QrScanned", "data" to string(data))
        }

        is MobileEvent.LocalNetworkAddressChanged -> {
            variant("LocalNetworkAddressChanged", "address" to nullable(address, ::string))
        }

        is MobileEvent.BleDeviceDiscovered -> {
            variant(
                "BleDeviceDiscovered",
                "id" to string(id),
                "rssi" to rssi.toString(),
                "adv_data" to bytes(advData),
            )
        }

        is MobileEvent.BleConnected -> {
            variant("BleConnected", "device_id" to string(deviceId), "direction" to direction(direction))
        }

        is MobileEvent.BleCharacteristicRead -> {
            variant(
                "BleCharacteristicRead",
                "device_id" to string(deviceId),
                "direction" to direction(direction),
                "uuid" to string(uuid),
                "data" to bytes(data),
            )
        }

        is MobileEvent.BleCharacteristicNotified -> {
            variant(
                "BleCharacteristicNotified",
                "device_id" to string(deviceId),
                "direction" to direction(direction),
                "uuid" to string(uuid),
                "data" to bytes(data),
            )
        }

        is MobileEvent.BleDisconnected -> {
            variant(
                "BleDisconnected",
                "device_id" to string(deviceId),
                "direction" to direction(direction),
                "reason" to string(reason),
            )
        }

        is MobileEvent.NfcDataReceived -> {
            variant("NfcDataReceived", "data" to bytes(data))
        }

        is MobileEvent.AudioSamplesRecorded -> {
            variant(
                "AudioSamplesRecorded",
                "samples" to floats(samples),
                "sample_rate" to sampleRate.toString(),
            )
        }

        is MobileEvent.AccelerometerData -> {
            variant(
                "AccelerometerData",
                "timestamp_ms" to timestampMs.toString(),
                "x_milli_g" to xMilliG.toString(),
                "y_milli_g" to yMilliG.toString(),
                "z_milli_g" to zMilliG.toString(),
            )
        }

        is MobileEvent.ImpactDetected -> {
            variant(
                "ImpactDetected",
                "timestamp_ms" to timestampMs.toString(),
                "magnitude_milli_g" to magnitudeMilliG.toString(),
            )
        }

        is MobileEvent.RelayEscrowReady -> {
            variant("RelayEscrowReady", "gate_hash" to bytes(gateHash))
        }

        is MobileEvent.RelayEscrowBlobReceived -> {
            variant("RelayEscrowBlobReceived", "gate_hash" to bytes(gateHash), "blob" to bytes(blob))
        }

        is MobileEvent.RelayEscrowFailed -> {
            variant("RelayEscrowFailed", "gate_hash" to bytes(gateHash), "reason" to string(reason))
        }

        is MobileEvent.LinkShared -> {
            unit("LinkShared")
        }

        is MobileEvent.LinkOpened -> {
            variant("LinkOpened", "peer_public_key" to bytes(peerPublicKey))
        }

        is MobileEvent.DirectPayloadReceived -> {
            variant("DirectPayloadReceived", "data" to bytes(data))
        }

        is MobileEvent.DirectCardReceived -> {
            variant("DirectCardReceived", "ciphertext" to bytes(ciphertext))
        }

        is MobileEvent.ImageReceived -> {
            variant("ImageReceived", "data" to bytes(data))
        }

        is MobileEvent.ImagePickCancelled -> {
            unit("ImagePickCancelled")
        }

        is MobileEvent.FilePickedFromUser -> {
            variant("FilePickedFromUser", "bytes" to bytes(bytes), "filename" to string(filename))
        }

        is MobileEvent.FilePickCancelledByUser -> {
            unit("FilePickCancelledByUser")
        }

        is MobileEvent.BiometricUnlockSucceeded -> {
            unit("BiometricUnlockSucceeded")
        }

        is MobileEvent.HardwareError -> {
            variant("HardwareError", "transport" to string(transport), "error" to string(error))
        }

        is MobileEvent.HardwareUnavailable -> {
            variant("HardwareUnavailable", "transport" to string(transport))
        }

        is MobileEvent.PermissionDenied -> {
            variant("PermissionDenied", "transport" to string(transport))
        }

        is MobileEvent.LocationResult -> {
            variant(
                "LocationResult",
                "latitude" to latitude.toString(),
                "longitude" to longitude.toString(),
                "accuracy_meters" to nullable(accuracyMeters) { it.toString() },
            )
        }
    }

private fun unit(name: String): String = string(name)

/** Fields are pre-encoded JSON values, appended verbatim in the given order. */
private fun variant(
    name: String,
    vararg fields: Pair<String, String>,
): String =
    buildString {
        append('{').append(string(name)).append(":{")
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(string(key)).append(':').append(value)
        }
        append("}}")
    }

private fun string(value: String): String = JsonPrimitive(value).toString()

private fun <T> nullable(
    value: T?,
    encode: (T) -> String,
): String = value?.let(encode) ?: "null"

private fun direction(direction: MobileBleLinkDirection): String =
    when (direction) {
        MobileBleLinkDirection.OUTBOUND -> string("Outbound")
        MobileBleLinkDirection.INBOUND -> string("Inbound")
    }

/**
 * Written straight into one buffer: a user-picked backup can reach 32 MiB,
 * and a boxed JsonArray of that many elements does not fit an Android heap.
 */
private fun bytes(data: ByteArray): String =
    data.joinTo(StringBuilder(data.size * 4 + 2), separator = ",", prefix = "[", postfix = "]") {
        (it.toInt() and 0xFF).toString()
    }.toString()

private fun floats(samples: List<Float>): String = samples.joinToString(separator = ",", prefix = "[", postfix = "]")
