// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.coreui

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin data classes matching core UI JSON types.
 *
 * These mirror the Rust types in `vauchi-core/src/ui/` and are serialized
 * using serde's default externally-tagged enum format. The JSON transport
 * layer is documented in `vauchi-mobile/src/mobile_ui.rs`.
 */

// ── ScreenModel ─────────────────────────────────────────────────────

@Serializable
data class ScreenModel(
    @SerialName("screen_id") val screenId: String,
    val title: String,
    val subtitle: String? = null,
    val components: List<Component>,
    val actions: List<ScreenAction>,
    val progress: Progress? = null,
)

@Serializable
data class Progress(
    @SerialName("current_step") val currentStep: Int,
    @SerialName("total_steps") val totalSteps: Int,
    val label: String? = null,
)

@Serializable
data class ScreenAction(
    val id: String,
    val label: String,
    val style: ActionStyle,
    val enabled: Boolean,
)

@Serializable
enum class ActionStyle {
    Primary,
    Secondary,
    Destructive,
}

// ── Component ───────────────────────────────────────────────────────

/**
 * Serde serializes Rust enums with struct variants as externally-tagged:
 * `{"Text": {"id": "...", "content": "...", "style": "Title"}}`
 *
 * For unit variants like `Divider`, serde produces `"Divider"`.
 *
 * Custom serializer needed because kotlinx.serialization uses a
 * discriminator-based format by default, not the externally-tagged format.
 */
@Serializable(with = ComponentSerializer::class)
sealed class Component {
    data class Text(
        val id: String,
        val content: String,
        val style: TextStyle,
    ) : Component()

    data class TextInput(
        val id: String,
        val label: String,
        val value: String,
        val placeholder: String? = null,
        val maxLength: Int? = null,
        val validationError: String? = null,
        val inputType: InputType,
    ) : Component()

    data class ToggleList(
        val id: String,
        val label: String,
        val items: List<ToggleItem>,
    ) : Component()

    data class FieldList(
        val id: String,
        val fields: List<FieldDisplay>,
        val visibilityMode: VisibilityMode,
        val availableGroups: List<String>,
    ) : Component()

    data class CardPreview(
        val name: String,
        val fields: List<FieldDisplay>,
        val groupViews: List<GroupCardView>,
        val selectedGroup: String? = null,
    ) : Component()

    data class InfoPanel(
        val id: String,
        val icon: String? = null,
        val title: String,
        val items: List<InfoItem>,
    ) : Component()

    data object Divider : Component()
}

// Helper data classes for deserialization of struct variant inner content.
@Serializable
private data class TextContent(
    val id: String,
    val content: String,
    val style: TextStyle,
)

@Serializable
private data class TextInputContent(
    val id: String,
    val label: String,
    val value: String,
    val placeholder: String? = null,
    @SerialName("max_length") val maxLength: Int? = null,
    @SerialName("validation_error") val validationError: String? = null,
    @SerialName("input_type") val inputType: InputType,
)

@Serializable
private data class ToggleListContent(
    val id: String,
    val label: String,
    val items: List<ToggleItem>,
)

@Serializable
private data class FieldListContent(
    val id: String,
    val fields: List<FieldDisplay>,
    @SerialName("visibility_mode") val visibilityMode: VisibilityMode,
    @SerialName("available_groups") val availableGroups: List<String>,
)

@Serializable
private data class CardPreviewContent(
    val name: String,
    val fields: List<FieldDisplay>,
    @SerialName("group_views") val groupViews: List<GroupCardView>,
    @SerialName("selected_group") val selectedGroup: String? = null,
)

@Serializable
private data class InfoPanelContent(
    val id: String,
    val icon: String? = null,
    val title: String,
    val items: List<InfoItem>,
)

internal object ComponentSerializer : KSerializer<Component> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Component")

    override fun deserialize(decoder: Decoder): Component {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                when (element.content) {
                    "Divider" -> Component.Divider

                    else -> throw IllegalArgumentException(
                        "Unknown Component variant: ${element.content}",
                    )
                }
            }

            is JsonObject -> {
                when {
                    "Text" in element -> {
                        val c: TextContent =
                            jsonDecoder.json.decodeFromJsonElement(element["Text"]!!)
                        Component.Text(id = c.id, content = c.content, style = c.style)
                    }

                    "TextInput" in element -> {
                        val c: TextInputContent =
                            jsonDecoder.json.decodeFromJsonElement(element["TextInput"]!!)
                        Component.TextInput(
                            id = c.id,
                            label = c.label,
                            value = c.value,
                            placeholder = c.placeholder,
                            maxLength = c.maxLength,
                            validationError = c.validationError,
                            inputType = c.inputType,
                        )
                    }

                    "ToggleList" in element -> {
                        val c: ToggleListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ToggleList"]!!)
                        Component.ToggleList(id = c.id, label = c.label, items = c.items)
                    }

                    "FieldList" in element -> {
                        val c: FieldListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["FieldList"]!!)
                        Component.FieldList(
                            id = c.id,
                            fields = c.fields,
                            visibilityMode = c.visibilityMode,
                            availableGroups = c.availableGroups,
                        )
                    }

                    "CardPreview" in element -> {
                        val c: CardPreviewContent =
                            jsonDecoder.json.decodeFromJsonElement(element["CardPreview"]!!)
                        Component.CardPreview(
                            name = c.name,
                            fields = c.fields,
                            groupViews = c.groupViews,
                            selectedGroup = c.selectedGroup,
                        )
                    }

                    "InfoPanel" in element -> {
                        val c: InfoPanelContent =
                            jsonDecoder.json.decodeFromJsonElement(element["InfoPanel"]!!)
                        Component.InfoPanel(
                            id = c.id,
                            icon = c.icon,
                            title = c.title,
                            items = c.items,
                        )
                    }

                    else -> {
                        throw IllegalArgumentException(
                            "Unknown Component variant: $element",
                        )
                    }
                }
            }

            else -> {
                throw IllegalArgumentException(
                    "Unexpected JSON element for Component: $element",
                )
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Component,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is Component.Divider -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("Divider"))
            }

            is Component.Text -> {
                val content = TextContent(id = value.id, content = value.content, style = value.style)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("Text" to inner)))
            }

            is Component.TextInput -> {
                val content =
                    TextInputContent(
                        id = value.id,
                        label = value.label,
                        value = value.value,
                        placeholder = value.placeholder,
                        maxLength = value.maxLength,
                        validationError = value.validationError,
                        inputType = value.inputType,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("TextInput" to inner)))
            }

            is Component.ToggleList -> {
                val content = ToggleListContent(id = value.id, label = value.label, items = value.items)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ToggleList" to inner)))
            }

            is Component.FieldList -> {
                val content =
                    FieldListContent(
                        id = value.id,
                        fields = value.fields,
                        visibilityMode = value.visibilityMode,
                        availableGroups = value.availableGroups,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("FieldList" to inner)))
            }

            is Component.CardPreview -> {
                val content =
                    CardPreviewContent(
                        name = value.name,
                        fields = value.fields,
                        groupViews = value.groupViews,
                        selectedGroup = value.selectedGroup,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("CardPreview" to inner)))
            }

            is Component.InfoPanel -> {
                val content =
                    InfoPanelContent(
                        id = value.id,
                        icon = value.icon,
                        title = value.title,
                        items = value.items,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("InfoPanel" to inner)))
            }
        }
    }
}

// ── Component sub-types ─────────────────────────────────────────────

@Serializable
enum class TextStyle {
    Title,
    Subtitle,
    Body,
    Caption,
}

@Serializable
enum class InputType {
    Text,
    Phone,
    Email,
}

@Serializable
enum class VisibilityMode {
    ShowHide,
    PerGroup,
}

@Serializable
data class ToggleItem(
    val id: String,
    val label: String,
    val selected: Boolean,
    val subtitle: String? = null,
)

@Serializable
data class FieldDisplay(
    val id: String,
    @SerialName("field_type") val fieldType: String,
    val label: String,
    val value: String,
    val visibility: UiFieldVisibility,
)

/**
 * Serde serializes this Rust enum as:
 * - `"Shown"` (unit variant)
 * - `"Hidden"` (unit variant)
 * - `{"Groups": ["Family", "Work"]}` (tuple variant wrapping Vec<String>)
 *
 * kotlinx.serialization's default sealed class handling doesn't match
 * serde's tuple variant format, so we use a custom serializer.
 */
@Serializable(with = UiFieldVisibilitySerializer::class)
sealed class UiFieldVisibility {
    data object Shown : UiFieldVisibility()

    data object Hidden : UiFieldVisibility()

    data class Groups(
        val groups: List<String>,
    ) : UiFieldVisibility()
}

internal object UiFieldVisibilitySerializer : KSerializer<UiFieldVisibility> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UiFieldVisibility")

    override fun deserialize(decoder: Decoder): UiFieldVisibility {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                when (element.content) {
                    "Shown" -> UiFieldVisibility.Shown

                    "Hidden" -> UiFieldVisibility.Hidden

                    else -> throw IllegalArgumentException(
                        "Unknown UiFieldVisibility variant: ${element.content}",
                    )
                }
            }

            is JsonObject -> {
                val groups =
                    element["Groups"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: throw IllegalArgumentException(
                            "Expected Groups key in UiFieldVisibility object: $element",
                        )
                UiFieldVisibility.Groups(groups)
            }

            else -> {
                throw IllegalArgumentException(
                    "Unexpected JSON element for UiFieldVisibility: $element",
                )
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: UiFieldVisibility,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is UiFieldVisibility.Shown -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("Shown"))
            }

            is UiFieldVisibility.Hidden -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("Hidden"))
            }

            is UiFieldVisibility.Groups -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "Groups" to
                                JsonArray(
                                    value.groups.map { JsonPrimitive(it) },
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }
        }
    }
}

@Serializable
data class GroupCardView(
    @SerialName("group_name") val groupName: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("visible_fields") val visibleFields: List<FieldDisplay>,
)

@Serializable
data class InfoItem(
    val icon: String? = null,
    val title: String,
    val detail: String,
)

// ── UserAction ──────────────────────────────────────────────────────

/**
 * Serde expects externally-tagged format for deserialization:
 * - `{"TextChanged": {"component_id": "...", "value": "..."}}`
 * - `{"ActionPressed": {"action_id": "get_started"}}`
 *
 * Custom serializer produces this format.
 */
@Serializable(with = UserActionSerializer::class)
sealed class UserAction {
    data class TextChanged(
        val componentId: String,
        val value: String,
    ) : UserAction()

    data class ItemToggled(
        val componentId: String,
        val itemId: String,
    ) : UserAction()

    data class ActionPressed(
        val actionId: String,
    ) : UserAction()

    data class FieldVisibilityChanged(
        val fieldId: String,
        val groupId: String? = null,
        val visible: Boolean,
    ) : UserAction()

    data class GroupViewSelected(
        val groupName: String? = null,
    ) : UserAction()
}

internal object UserActionSerializer : KSerializer<UserAction> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UserAction")

    override fun serialize(
        encoder: Encoder,
        value: UserAction,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        val element =
            when (value) {
                is UserAction.TextChanged -> {
                    JsonObject(
                        mapOf(
                            "TextChanged" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "value" to JsonPrimitive(value.value),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.ItemToggled -> {
                    JsonObject(
                        mapOf(
                            "ItemToggled" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "item_id" to JsonPrimitive(value.itemId),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.ActionPressed -> {
                    JsonObject(
                        mapOf(
                            "ActionPressed" to
                                JsonObject(
                                    mapOf("action_id" to JsonPrimitive(value.actionId)),
                                ),
                        ),
                    )
                }

                is UserAction.FieldVisibilityChanged -> {
                    JsonObject(
                        mapOf(
                            "FieldVisibilityChanged" to
                                JsonObject(
                                    mapOf(
                                        "field_id" to JsonPrimitive(value.fieldId),
                                        "group_id" to
                                            if (value.groupId != null) {
                                                JsonPrimitive(value.groupId)
                                            } else {
                                                JsonNull
                                            },
                                        "visible" to JsonPrimitive(value.visible),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.GroupViewSelected -> {
                    JsonObject(
                        mapOf(
                            "GroupViewSelected" to
                                JsonObject(
                                    mapOf(
                                        "group_name" to
                                            if (value.groupName != null) {
                                                JsonPrimitive(value.groupName)
                                            } else {
                                                JsonNull
                                            },
                                    ),
                                ),
                        ),
                    )
                }
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): UserAction {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement() as JsonObject
        return when {
            "TextChanged" in element -> {
                val obj = element["TextChanged"] as JsonObject
                UserAction.TextChanged(
                    componentId = obj["component_id"]!!.jsonPrimitive.content,
                    value = obj["value"]!!.jsonPrimitive.content,
                )
            }

            "ItemToggled" in element -> {
                val obj = element["ItemToggled"] as JsonObject
                UserAction.ItemToggled(
                    componentId = obj["component_id"]!!.jsonPrimitive.content,
                    itemId = obj["item_id"]!!.jsonPrimitive.content,
                )
            }

            "ActionPressed" in element -> {
                val obj = element["ActionPressed"] as JsonObject
                UserAction.ActionPressed(
                    actionId = obj["action_id"]!!.jsonPrimitive.content,
                )
            }

            "FieldVisibilityChanged" in element -> {
                val obj = element["FieldVisibilityChanged"] as JsonObject
                UserAction.FieldVisibilityChanged(
                    fieldId = obj["field_id"]!!.jsonPrimitive.content,
                    groupId = obj["group_id"]?.jsonPrimitive?.contentOrNull,
                    visible = obj["visible"]!!.jsonPrimitive.content.toBooleanStrict(),
                )
            }

            "GroupViewSelected" in element -> {
                val obj = element["GroupViewSelected"] as JsonObject
                UserAction.GroupViewSelected(
                    groupName = obj["group_name"]?.jsonPrimitive?.contentOrNull,
                )
            }

            else -> {
                throw IllegalArgumentException("Unknown UserAction variant: $element")
            }
        }
    }
}

// ── ActionResult ────────────────────────────────────────────────────

/**
 * Serde serializes this Rust enum as:
 * - `{"UpdateScreen": {screen fields...}}` (newtype variant)
 * - `{"NavigateTo": {screen fields...}}` (newtype variant)
 * - `{"ValidationError": {"component_id": "...", "message": "..."}}` (struct variant)
 * - `"Complete"` (unit variant)
 *
 * Custom serializer needed because kotlinx.serialization doesn't natively
 * handle the mix of newtype/struct/unit variant formats that serde produces.
 */
@Serializable(with = ActionResultSerializer::class)
sealed class ActionResult {
    data class UpdateScreen(
        val screen: ScreenModel,
    ) : ActionResult()

    data class NavigateTo(
        val screen: ScreenModel,
    ) : ActionResult()

    data class ValidationError(
        val componentId: String,
        val message: String,
    ) : ActionResult()

    data object Complete : ActionResult()
}

internal object ActionResultSerializer : KSerializer<ActionResult> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ActionResult")

    override fun deserialize(decoder: Decoder): ActionResult {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                when (element.content) {
                    "Complete" -> ActionResult.Complete

                    else -> throw IllegalArgumentException(
                        "Unknown ActionResult variant: ${element.content}",
                    )
                }
            }

            is JsonObject -> {
                when {
                    "UpdateScreen" in element -> {
                        val screen: ScreenModel =
                            jsonDecoder.json.decodeFromJsonElement(element["UpdateScreen"]!!)
                        ActionResult.UpdateScreen(screen)
                    }

                    "NavigateTo" in element -> {
                        val screen: ScreenModel =
                            jsonDecoder.json.decodeFromJsonElement(element["NavigateTo"]!!)
                        ActionResult.NavigateTo(screen)
                    }

                    "ValidationError" in element -> {
                        val obj = element["ValidationError"] as JsonObject
                        ActionResult.ValidationError(
                            componentId = obj["component_id"]!!.jsonPrimitive.content,
                            message = obj["message"]!!.jsonPrimitive.content,
                        )
                    }

                    else -> {
                        throw IllegalArgumentException(
                            "Unknown ActionResult variant: $element",
                        )
                    }
                }
            }

            else -> {
                throw IllegalArgumentException(
                    "Unexpected JSON element for ActionResult: $element",
                )
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: ActionResult,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is ActionResult.Complete -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("Complete"))
            }

            is ActionResult.UpdateScreen -> {
                val screenElement = jsonEncoder.json.encodeToJsonElement(value.screen)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("UpdateScreen" to screenElement)))
            }

            is ActionResult.NavigateTo -> {
                val screenElement = jsonEncoder.json.encodeToJsonElement(value.screen)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("NavigateTo" to screenElement)))
            }

            is ActionResult.ValidationError -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "ValidationError" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "message" to JsonPrimitive(value.message),
                                    ),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }
        }
    }
}
