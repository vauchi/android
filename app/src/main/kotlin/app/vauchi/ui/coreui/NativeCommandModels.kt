// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import uniffi.vauchi_platform.MobileNotificationCategory
import uniffi.vauchi_platform.MobileNotificationPriority
import uniffi.vauchi_platform.MobilePendingNotification

/**
 * Native effects emitted by Core that Android executes through platform APIs.
 *
 * Presentation nodes, navigation, contextual actions, and overlays deliberately
 * do not live here; those cross the generic Event/Command presentation boundary.
 */
@Serializable(with = CommandDTOSerializer::class)
sealed class CommandDTO {
    data class QrDisplay(
        val data: String,
    ) : CommandDTO()

    data object QrRequestScan : CommandDTO()

    data class BleStartScanning(
        val serviceUuid: String,
    ) : CommandDTO()

    data class BleStartAdvertising(
        val serviceUuid: String,
        val payload: List<Int>,
    ) : CommandDTO()

    data class BleConnect(
        val deviceId: String,
    ) : CommandDTO()

    data class BleWriteCharacteristic(
        val deviceId: String,
        val direction: BleLinkDirectionDTO,
        val uuid: String,
        val data: List<Int>,
    ) : CommandDTO()

    data class BleReadCharacteristic(
        val deviceId: String,
        val direction: BleLinkDirectionDTO,
        val uuid: String,
    ) : CommandDTO()

    data class BleDisconnect(
        val deviceId: String,
        val direction: BleLinkDirectionDTO,
    ) : CommandDTO()

    data class ScheduleWakeup(
        @SerialName("earliest_secs") val earliestSecs: UInt,
        @SerialName("deadline_secs") val deadlineSecs: UInt,
    ) : CommandDTO()

    data class NfcActivate(
        val payload: List<Int>,
    ) : CommandDTO()

    data object NfcDeactivate : CommandDTO()

    data class NfcSendApdu(
        val data: List<Int>,
    ) : CommandDTO()

    data class AudioEmitChallenge(
        val samples: List<Float>,
        val sampleRate: UInt,
    ) : CommandDTO()

    data class AudioListenForResponse(
        val timeoutMs: Long,
        val sampleRate: UInt,
    ) : CommandDTO()

    data object AudioStop : CommandDTO()

    data class LocationRequest(
        val timeoutMs: Long,
    ) : CommandDTO()

    data object AccelerometerStart : CommandDTO()

    data object AccelerometerStop : CommandDTO()

    data object ImagePickFromLibrary : CommandDTO()

    data object ImageCaptureFromCamera : CommandDTO()

    data object ImagePickFromFile : CommandDTO()

    data class FilePickFromUser(
        val acceptedMimeTypes: List<String>,
        val purpose: String,
    ) : CommandDTO()

    data class SetScreenBrightness(
        val level: Float?,
    ) : CommandDTO()

    data class SetIdleTimerDisabled(
        val disabled: Boolean,
    ) : CommandDTO()

    data class ShowShareSheet(
        val url: String,
    ) : CommandDTO()

    data class SwitchCamera(
        val useFront: Boolean,
    ) : CommandDTO()

    data class SetOrientationLock(
        val orientation: OrientationDTO?,
    ) : CommandDTO()

    data class Celebrate(
        val haptic: String,
        val sound: String,
        val animation: String,
    ) : CommandDTO()

    data class Unknown(
        val variantName: String,
    ) : CommandDTO()
}

/** Mirrors Core's platform orientation tokens. */
enum class OrientationDTO {
    Portrait,
    Landscape,
}

internal object CommandDTOSerializer : KSerializer<CommandDTO> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("CommandDTO")

    override fun deserialize(decoder: Decoder): CommandDTO {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> decodePrimitiveCommand(element)
            is JsonObject -> decodeObjectCommand(element)
            else -> CommandDTO.Unknown("?")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: CommandDTO,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(JsonPrimitive("CommandDTO"))
    }

    private fun decodePrimitiveCommand(element: JsonPrimitive): CommandDTO =
        when (element.content) {
            "QrRequestScan" -> CommandDTO.QrRequestScan
            "NfcDeactivate" -> CommandDTO.NfcDeactivate
            "AudioStop" -> CommandDTO.AudioStop
            "AccelerometerStart" -> CommandDTO.AccelerometerStart
            "AccelerometerStop" -> CommandDTO.AccelerometerStop
            "ImagePickFromLibrary" -> CommandDTO.ImagePickFromLibrary
            "ImageCaptureFromCamera" -> CommandDTO.ImageCaptureFromCamera
            "ImagePickFromFile" -> CommandDTO.ImagePickFromFile
            else -> CommandDTO.Unknown(element.content)
        }

    private fun decodeObjectCommand(element: JsonObject): CommandDTO =
        when {
            "ScheduleWakeup" in element -> {
                val obj = element.objectValue("ScheduleWakeup")
                CommandDTO.ScheduleWakeup(
                    earliestSecs = obj.value("earliest_secs").int.toUInt(),
                    deadlineSecs = obj.value("deadline_secs").int.toUInt(),
                )
            }

            "QrDisplay" in element -> {
                val obj = element.objectValue("QrDisplay")
                CommandDTO.QrDisplay(data = obj.value("data").content)
            }

            "BleStartScanning" in element -> {
                val obj = element.objectValue("BleStartScanning")
                CommandDTO.BleStartScanning(serviceUuid = obj.value("service_uuid").content)
            }

            "BleStartAdvertising" in element -> {
                val obj = element.objectValue("BleStartAdvertising")
                CommandDTO.BleStartAdvertising(
                    serviceUuid = obj.value("service_uuid").content,
                    payload = obj.arrayInts("payload"),
                )
            }

            "BleConnect" in element -> {
                val obj = element.objectValue("BleConnect")
                CommandDTO.BleConnect(deviceId = obj.value("device_id").content)
            }

            "BleWriteCharacteristic" in element -> {
                val obj = element.objectValue("BleWriteCharacteristic")
                CommandDTO.BleWriteCharacteristic(
                    deviceId = obj.value("device_id").content,
                    direction = BleLinkDirectionDTO.valueOf(obj.value("direction").content),
                    uuid = obj.value("uuid").content,
                    data = obj.arrayInts("data"),
                )
            }

            "BleReadCharacteristic" in element -> {
                val obj = element.objectValue("BleReadCharacteristic")
                CommandDTO.BleReadCharacteristic(
                    deviceId = obj.value("device_id").content,
                    direction = BleLinkDirectionDTO.valueOf(obj.value("direction").content),
                    uuid = obj.value("uuid").content,
                )
            }

            "BleDisconnect" in element -> {
                val obj = element.objectValue("BleDisconnect")
                CommandDTO.BleDisconnect(
                    deviceId = obj.value("device_id").content,
                    direction = BleLinkDirectionDTO.valueOf(obj.value("direction").content),
                )
            }

            "NfcActivate" in element -> {
                CommandDTO.NfcActivate(element.objectValue("NfcActivate").arrayInts("payload"))
            }

            "NfcSendApdu" in element -> {
                CommandDTO.NfcSendApdu(element.objectValue("NfcSendApdu").arrayInts("data"))
            }

            "AudioEmitChallenge" in element -> {
                val obj = element.objectValue("AudioEmitChallenge")
                CommandDTO.AudioEmitChallenge(
                    samples = obj.getValue("samples").jsonArray.map { it.jsonPrimitive.float },
                    sampleRate = obj.value("sample_rate").int.toUInt(),
                )
            }

            "AudioListenForResponse" in element -> {
                val obj = element.objectValue("AudioListenForResponse")
                CommandDTO.AudioListenForResponse(
                    timeoutMs = obj.value("timeout_ms").long,
                    sampleRate = obj.value("sample_rate").int.toUInt(),
                )
            }

            "LocationRequest" in element -> {
                CommandDTO.LocationRequest(
                    element.objectValue("LocationRequest").value("timeout_ms").long,
                )
            }

            "SetScreenBrightness" in element -> {
                val obj = element.objectValue("SetScreenBrightness")
                CommandDTO.SetScreenBrightness(
                    level = obj["level"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.float,
                )
            }

            "SetIdleTimerDisabled" in element -> {
                CommandDTO.SetIdleTimerDisabled(
                    element.objectValue("SetIdleTimerDisabled").value("disabled").boolean,
                )
            }

            "ShowShareSheet" in element -> {
                CommandDTO.ShowShareSheet(
                    element.objectValue("ShowShareSheet").value("url").content,
                )
            }

            "SwitchCamera" in element -> {
                CommandDTO.SwitchCamera(
                    element.objectValue("SwitchCamera").value("use_front").boolean,
                )
            }

            "SetOrientationLock" in element -> {
                val obj = element.objectValue("SetOrientationLock")
                val orientation =
                    obj["orientation"]
                        ?.takeIf { it !is JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let { runCatching { OrientationDTO.valueOf(it) }.getOrNull() }
                CommandDTO.SetOrientationLock(orientation)
            }

            "FilePickFromUser" in element -> {
                val obj = element.objectValue("FilePickFromUser")
                val purposeElement = obj.getValue("purpose")
                val purpose =
                    if (purposeElement is JsonObject) {
                        (purposeElement["Other"] as? JsonObject)
                            ?.get("label_key")
                            ?.jsonPrimitive
                            ?.content ?: "Other"
                    } else {
                        purposeElement.jsonPrimitive.content
                    }
                CommandDTO.FilePickFromUser(
                    acceptedMimeTypes =
                        obj.getValue("accepted_mime_types").jsonArray.map {
                            it.jsonPrimitive.content
                        },
                    purpose = purpose,
                )
            }

            "Celebrate" in element -> {
                val obj = element.objectValue("Celebrate")
                CommandDTO.Celebrate(
                    haptic = obj.value("haptic").content,
                    sound = obj.value("sound").content,
                    animation = obj.value("animation").content,
                )
            }

            else -> {
                CommandDTO.Unknown(element.keys.firstOrNull() ?: "?")
            }
        }

    private fun JsonObject.objectValue(key: String): JsonObject = getValue(key) as JsonObject

    private fun JsonObject.value(key: String): JsonPrimitive = getValue(key).jsonPrimitive

    private fun JsonObject.arrayInts(key: String): List<Int> = getValue(key).jsonArray.map { it.jsonPrimitive.int }
}

enum class BleLinkDirectionDTO {
    Outbound,
    Inbound,
}

@Serializable
data class WakeupOutcome(
    val notifications: List<MobilePendingNotificationDTO> = emptyList(),
    val commands: List<CommandDTO> = emptyList(),
)

@Serializable
data class MobilePendingNotificationDTO(
    @SerialName("event_key") val eventKey: String,
    val category: MobileNotificationCategoryDTO,
    val title: String,
    val body: String,
    @SerialName("contact_id") val contactId: String,
    @SerialName("deep_link_uri") val deepLinkUri: String? = null,
    @SerialName("os_category_id") val osCategoryId: String,
    @SerialName("os_channel_id") val osChannelId: String,
    val priority: MobileNotificationPriorityDTO,
    @SerialName("os_category_options") val osCategoryOptions: List<String> = emptyList(),
)

@Serializable
enum class MobileNotificationCategoryDTO {
    EmergencyAlert,
    DuressAlert,
    ContactAdded,
    CardUpdate,
}

@Serializable
enum class MobileNotificationPriorityDTO {
    Default,
    High,
    Urgent,
}

fun MobilePendingNotificationDTO.toMobile(): MobilePendingNotification =
    MobilePendingNotification(
        eventKey = eventKey,
        category =
            when (category) {
                MobileNotificationCategoryDTO.EmergencyAlert -> {
                    MobileNotificationCategory.EMERGENCY_ALERT
                }

                MobileNotificationCategoryDTO.DuressAlert -> {
                    MobileNotificationCategory.DURESS_ALERT
                }

                MobileNotificationCategoryDTO.ContactAdded -> {
                    MobileNotificationCategory.CONTACT_ADDED
                }

                MobileNotificationCategoryDTO.CardUpdate -> {
                    MobileNotificationCategory.CARD_UPDATE
                }
            },
        title = title,
        body = body,
        contactId = contactId,
        deepLinkUri = deepLinkUri,
        osCategoryId = osCategoryId,
        osChannelId = osChannelId,
        priority =
            when (priority) {
                MobileNotificationPriorityDTO.Default -> MobileNotificationPriority.DEFAULT
                MobileNotificationPriorityDTO.High -> MobileNotificationPriority.HIGH
                MobileNotificationPriorityDTO.Urgent -> MobileNotificationPriority.URGENT
            },
        osCategoryOptions = osCategoryOptions,
    )
