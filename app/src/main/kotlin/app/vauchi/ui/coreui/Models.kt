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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Kotlin data classes matching core UI JSON types.
 *
 * These mirror the Rust types in `vauchi-core/src/ui/` and are serialized
 * using serde's default externally-tagged enum format. The JSON transport
 * layer is documented in `vauchi-platform/src/mobile_ui.rs`.
 */

// ── Design Tokens ──────────────────────────────────────────────────

/** Layout tokens for consistent cross-platform rendering. */
@Serializable
data class DesignTokens(
    val spacing: SpacingTokens,
    @SerialName("spacing_direction") val spacingDirection: SpacingDirectionTokens,
    val typography: TypographyTokens,
    @SerialName("border_radius") val borderRadius: BorderRadiusTokens,
    @SerialName("touch_target") val touchTarget: TouchTargetTokens,
    val motion: MotionTokens,
) {
    companion object {
        val DEFAULT =
            DesignTokens(
                spacing = SpacingTokens(xs = 4, sm = 8, md = 16, lg = 24, xl = 32),
                spacingDirection = SpacingDirectionTokens(contentStart = 16, contentEnd = 16, listItemStart = 8, listItemEnd = 8),
                typography = TypographyTokens(titleSize = 24, subtitleSize = 18, bodySize = 16, captionSize = 14),
                borderRadius = BorderRadiusTokens(sm = 4, md = 8, mdLg = 12, lg = 16),
                touchTarget = TouchTargetTokens(minimum = 44),
                motion = MotionTokens(enterDurationMs = 200, exitDurationMs = 150, emphasisDurationMs = 300),
            )
    }
}

@Serializable
data class SpacingTokens(
    val xs: Int,
    val sm: Int,
    val md: Int,
    val lg: Int,
    val xl: Int,
)

@Serializable
data class SpacingDirectionTokens(
    @SerialName("content_start") val contentStart: Int,
    @SerialName("content_end") val contentEnd: Int,
    @SerialName("list_item_start") val listItemStart: Int,
    @SerialName("list_item_end") val listItemEnd: Int,
    @SerialName("list_item_inline_start") val listItemInlineStart: Int = 12,
    @SerialName("list_item_inline_end") val listItemInlineEnd: Int = 12,
)

@Serializable
data class TypographyTokens(
    @SerialName("title_size") val titleSize: Int,
    @SerialName("subtitle_size") val subtitleSize: Int,
    @SerialName("body_size") val bodySize: Int,
    @SerialName("caption_size") val captionSize: Int,
)

@Serializable
data class BorderRadiusTokens(
    val sm: Int,
    val md: Int,
    @SerialName("md_lg") val mdLg: Int,
    val lg: Int,
)

@Serializable
data class TouchTargetTokens(
    val minimum: Int,
)

@Serializable
data class MotionTokens(
    @SerialName("enter_duration_ms") val enterDurationMs: Int,
    @SerialName("exit_duration_ms") val exitDurationMs: Int,
    @SerialName("emphasis_duration_ms") val emphasisDurationMs: Int,
)

// ── ScreenModel ─────────────────────────────────────────────────────

@Serializable
data class ScreenModel(
    @SerialName("screen_id") val screenId: String,
    val title: String,
    val subtitle: String? = null,
    val components: List<Component>,
    val actions: List<ScreenAction>,
    val progress: Progress? = null,
    val tokens: DesignTokens = DesignTokens.DEFAULT,
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
        val a11y: A11y? = null,
    ) : Component()

    data class ToggleList(
        val id: String,
        val label: String,
        val items: List<ToggleItem>,
        val a11y: A11y? = null,
    ) : Component()

    data class FieldList(
        val id: String,
        val fields: List<FieldDisplay>,
        val visibilityMode: VisibilityMode,
        val availableGroups: List<String>,
        val a11y: A11y? = null,
    ) : Component()

    data class CardPreview(
        val name: String,
        val fields: List<FieldDisplay>,
        val groupViews: List<GroupCardView>,
        val selectedGroup: String? = null,
        val avatarData: List<Int>? = null,
        val a11y: A11y? = null,
    ) : Component()

    data class InfoPanel(
        val id: String,
        val icon: String? = null,
        val title: String,
        val items: List<InfoItem>,
        val a11y: A11y? = null,
    ) : Component()

    data class ContactList(
        val id: String,
        val contacts: List<ContactItem>,
        val searchable: Boolean,
    ) : Component()

    data class SettingsGroup(
        val id: String,
        val label: String,
        val items: List<SettingsItem>,
    ) : Component()

    data class ActionList(
        val id: String,
        val items: List<ActionListItem>,
    ) : Component()

    data class StatusIndicator(
        val id: String,
        val icon: String? = null,
        val title: String,
        val detail: String? = null,
        val status: Status,
        val a11y: A11y? = null,
    ) : Component()

    data class PinInput(
        val id: String,
        val label: String,
        val length: Int,
        val masked: Boolean,
        val validationError: String? = null,
        val a11y: A11y? = null,
    ) : Component()

    data class QrCode(
        val id: String,
        val data: String,
        val mode: QrMode,
        val label: String? = null,
        val a11y: A11y? = null,
    ) : Component()

    data class ConfirmationDialog(
        val id: String,
        val title: String,
        val message: String,
        val confirmText: String,
        val destructive: Boolean,
    ) : Component()

    data class ShowToast(
        val id: String,
        val message: String,
        val undoActionId: String? = null,
        val durationMs: Int,
    ) : Component()

    data class InlineConfirm(
        val id: String,
        val warning: String,
        val confirmText: String,
        val cancelText: String,
        val destructive: Boolean,
        val a11y: A11y? = null,
    ) : Component()

    data class EditableText(
        val id: String,
        val label: String,
        val value: String,
        val editing: Boolean,
        val validationError: String? = null,
        val a11y: A11y? = null,
    ) : Component()

    data class Banner(
        val text: String,
        val actionLabel: String,
        val actionId: String,
        val a11y: A11y? = null,
    ) : Component()

    data class Dropdown(
        val id: String,
        val label: String,
        val selected: String?,
        val options: List<DropdownOption>,
        val a11y: A11y? = null,
    ) : Component()

    data class AvatarPreview(
        val id: String,
        val imageData: List<Int>?,
        val initials: String,
        val bgColor: List<Int>?,
        val brightness: Float,
        val editable: Boolean,
        val a11y: A11y? = null,
    ) : Component()

    data class Slider(
        val id: String,
        val label: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val minIcon: String?,
        val maxIcon: String?,
        val a11y: A11y? = null,
    ) : Component()

    data object Divider : Component()

    /** Unknown component from a newer core version — render as empty space. */
    data object Unknown : Component()
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
    val a11y: A11y? = null,
)

@Serializable
private data class ToggleListContent(
    val id: String,
    val label: String,
    val items: List<ToggleItem>,
    val a11y: A11y? = null,
)

@Serializable
private data class FieldListContent(
    val id: String,
    val fields: List<FieldDisplay>,
    @SerialName("visibility_mode") val visibilityMode: VisibilityMode,
    @SerialName("available_groups") val availableGroups: List<String>,
    val a11y: A11y? = null,
)

@Serializable
private data class CardPreviewContent(
    val name: String,
    val fields: List<FieldDisplay>,
    @SerialName("group_views") val groupViews: List<GroupCardView>,
    @SerialName("selected_group") val selectedGroup: String? = null,
    @SerialName("avatar_data") val avatarData: List<Int>? = null,
    val a11y: A11y? = null,
)

@Serializable
private data class InfoPanelContent(
    val id: String,
    val icon: String? = null,
    val title: String,
    val items: List<InfoItem>,
    val a11y: A11y? = null,
)

@Serializable
private data class ContactListContent(
    val id: String,
    val contacts: List<ContactItem>,
    val searchable: Boolean,
)

@Serializable
private data class SettingsGroupContent(
    val id: String,
    val label: String,
    val items: List<SettingsItem>,
)

@Serializable
private data class ActionListContent(
    val id: String,
    val items: List<ActionListItem>,
)

@Serializable
private data class StatusIndicatorContent(
    val id: String,
    val icon: String? = null,
    val title: String,
    val detail: String? = null,
    val status: Status,
    val a11y: A11y? = null,
)

@Serializable
private data class PinInputContent(
    val id: String,
    val label: String,
    val length: Int,
    val masked: Boolean,
    @SerialName("validation_error") val validationError: String? = null,
    val a11y: A11y? = null,
)

@Serializable
private data class QrCodeContent(
    val id: String,
    val data: String,
    val mode: QrMode,
    val label: String? = null,
    val a11y: A11y? = null,
)

@Serializable
private data class ConfirmationDialogContent(
    val id: String,
    val title: String,
    val message: String,
    @SerialName("confirm_text") val confirmText: String,
    val destructive: Boolean,
)

@Serializable
private data class ShowToastContent(
    val id: String,
    val message: String,
    @SerialName("undo_action_id") val undoActionId: String? = null,
    @SerialName("duration_ms") val durationMs: Int,
)

@Serializable
private data class InlineConfirmContent(
    val id: String,
    val warning: String,
    @SerialName("confirm_text") val confirmText: String,
    @SerialName("cancel_text") val cancelText: String,
    val destructive: Boolean,
    val a11y: A11y? = null,
)

@Serializable
private data class EditableTextContent(
    val id: String,
    val label: String,
    val value: String,
    val editing: Boolean,
    @SerialName("validation_error") val validationError: String? = null,
    val a11y: A11y? = null,
)

@Serializable
private data class BannerContent(
    val text: String,
    @SerialName("action_label") val actionLabel: String,
    @SerialName("action_id") val actionId: String,
    val a11y: A11y? = null,
)

@Serializable
private data class DropdownContent(
    val id: String,
    val label: String,
    val selected: String? = null,
    val options: List<DropdownOption>,
    val a11y: A11y? = null,
)

@Serializable
private data class AvatarPreviewContent(
    val id: String,
    @SerialName("image_data") val imageData: List<Int>? = null,
    val initials: String,
    @SerialName("bg_color") val bgColor: List<Int>? = null,
    val brightness: Float,
    val editable: Boolean,
    val a11y: A11y? = null,
)

@Serializable
private data class SliderContent(
    val id: String,
    val label: String,
    val value: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    @SerialName("min_icon") val minIcon: String? = null,
    @SerialName("max_icon") val maxIcon: String? = null,
    val a11y: A11y? = null,
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

                    // Unknown unit variant — core is newer than this shell
                    else -> Component.Unknown
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
                            a11y = c.a11y,
                        )
                    }

                    "ToggleList" in element -> {
                        val c: ToggleListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ToggleList"]!!)
                        Component.ToggleList(id = c.id, label = c.label, items = c.items, a11y = c.a11y)
                    }

                    "FieldList" in element -> {
                        val c: FieldListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["FieldList"]!!)
                        Component.FieldList(
                            id = c.id,
                            fields = c.fields,
                            visibilityMode = c.visibilityMode,
                            availableGroups = c.availableGroups,
                            a11y = c.a11y,
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
                            avatarData = c.avatarData,
                            a11y = c.a11y,
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
                            a11y = c.a11y,
                        )
                    }

                    "ContactList" in element -> {
                        val c: ContactListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ContactList"]!!)
                        Component.ContactList(id = c.id, contacts = c.contacts, searchable = c.searchable)
                    }

                    "SettingsGroup" in element -> {
                        val c: SettingsGroupContent =
                            jsonDecoder.json.decodeFromJsonElement(element["SettingsGroup"]!!)
                        Component.SettingsGroup(id = c.id, label = c.label, items = c.items)
                    }

                    "ActionList" in element -> {
                        val c: ActionListContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ActionList"]!!)
                        Component.ActionList(id = c.id, items = c.items)
                    }

                    "StatusIndicator" in element -> {
                        val c: StatusIndicatorContent =
                            jsonDecoder.json.decodeFromJsonElement(element["StatusIndicator"]!!)
                        Component.StatusIndicator(
                            id = c.id,
                            icon = c.icon,
                            title = c.title,
                            detail = c.detail,
                            status = c.status,
                            a11y = c.a11y,
                        )
                    }

                    "PinInput" in element -> {
                        val c: PinInputContent =
                            jsonDecoder.json.decodeFromJsonElement(element["PinInput"]!!)
                        Component.PinInput(
                            id = c.id,
                            label = c.label,
                            length = c.length,
                            masked = c.masked,
                            validationError = c.validationError,
                            a11y = c.a11y,
                        )
                    }

                    "QrCode" in element -> {
                        val c: QrCodeContent =
                            jsonDecoder.json.decodeFromJsonElement(element["QrCode"]!!)
                        Component.QrCode(id = c.id, data = c.data, mode = c.mode, label = c.label, a11y = c.a11y)
                    }

                    "ConfirmationDialog" in element -> {
                        val c: ConfirmationDialogContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ConfirmationDialog"]!!)
                        Component.ConfirmationDialog(
                            id = c.id,
                            title = c.title,
                            message = c.message,
                            confirmText = c.confirmText,
                            destructive = c.destructive,
                        )
                    }

                    "ShowToast" in element -> {
                        val c: ShowToastContent =
                            jsonDecoder.json.decodeFromJsonElement(element["ShowToast"]!!)
                        Component.ShowToast(
                            id = c.id,
                            message = c.message,
                            undoActionId = c.undoActionId,
                            durationMs = c.durationMs,
                        )
                    }

                    "InlineConfirm" in element -> {
                        val c: InlineConfirmContent =
                            jsonDecoder.json.decodeFromJsonElement(element["InlineConfirm"]!!)
                        Component.InlineConfirm(
                            id = c.id,
                            warning = c.warning,
                            confirmText = c.confirmText,
                            cancelText = c.cancelText,
                            destructive = c.destructive,
                            a11y = c.a11y,
                        )
                    }

                    "EditableText" in element -> {
                        val c: EditableTextContent =
                            jsonDecoder.json.decodeFromJsonElement(element["EditableText"]!!)
                        Component.EditableText(
                            id = c.id,
                            label = c.label,
                            value = c.value,
                            editing = c.editing,
                            validationError = c.validationError,
                            a11y = c.a11y,
                        )
                    }

                    "Banner" in element -> {
                        val c: BannerContent =
                            jsonDecoder.json.decodeFromJsonElement(element["Banner"]!!)
                        Component.Banner(
                            text = c.text,
                            actionLabel = c.actionLabel,
                            actionId = c.actionId,
                            a11y = c.a11y,
                        )
                    }

                    "Dropdown" in element -> {
                        val c: DropdownContent =
                            jsonDecoder.json.decodeFromJsonElement(element["Dropdown"]!!)
                        Component.Dropdown(id = c.id, label = c.label, selected = c.selected, options = c.options, a11y = c.a11y)
                    }

                    "AvatarPreview" in element -> {
                        val c: AvatarPreviewContent =
                            jsonDecoder.json.decodeFromJsonElement(element["AvatarPreview"]!!)
                        Component.AvatarPreview(
                            id = c.id,
                            imageData = c.imageData,
                            initials = c.initials,
                            bgColor = c.bgColor,
                            brightness = c.brightness,
                            editable = c.editable,
                            a11y = c.a11y,
                        )
                    }

                    "Slider" in element -> {
                        val c: SliderContent =
                            jsonDecoder.json.decodeFromJsonElement(element["Slider"]!!)
                        Component.Slider(
                            id = c.id,
                            label = c.label,
                            value = c.value,
                            min = c.min,
                            max = c.max,
                            step = c.step,
                            minIcon = c.minIcon,
                            maxIcon = c.maxIcon,
                            a11y = c.a11y,
                        )
                    }

                    // Unknown struct variant — core is newer than this shell
                    else -> {
                        Component.Unknown
                    }
                }
            }

            // Unexpected JSON structure — degrade gracefully
            else -> {
                Component.Unknown
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
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("TextInput" to inner)))
            }

            is Component.ToggleList -> {
                val content = ToggleListContent(id = value.id, label = value.label, items = value.items, a11y = value.a11y)
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
                        a11y = value.a11y,
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
                        avatarData = value.avatarData,
                        a11y = value.a11y,
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
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("InfoPanel" to inner)))
            }

            is Component.ContactList -> {
                val content =
                    ContactListContent(id = value.id, contacts = value.contacts, searchable = value.searchable)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ContactList" to inner)))
            }

            is Component.SettingsGroup -> {
                val content =
                    SettingsGroupContent(id = value.id, label = value.label, items = value.items)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("SettingsGroup" to inner)))
            }

            is Component.ActionList -> {
                val content = ActionListContent(id = value.id, items = value.items)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ActionList" to inner)))
            }

            is Component.StatusIndicator -> {
                val content =
                    StatusIndicatorContent(
                        id = value.id,
                        icon = value.icon,
                        title = value.title,
                        detail = value.detail,
                        status = value.status,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("StatusIndicator" to inner)))
            }

            is Component.PinInput -> {
                val content =
                    PinInputContent(
                        id = value.id,
                        label = value.label,
                        length = value.length,
                        masked = value.masked,
                        validationError = value.validationError,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("PinInput" to inner)))
            }

            is Component.QrCode -> {
                val content =
                    QrCodeContent(id = value.id, data = value.data, mode = value.mode, label = value.label, a11y = value.a11y)
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("QrCode" to inner)))
            }

            is Component.ConfirmationDialog -> {
                val content =
                    ConfirmationDialogContent(
                        id = value.id,
                        title = value.title,
                        message = value.message,
                        confirmText = value.confirmText,
                        destructive = value.destructive,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ConfirmationDialog" to inner)))
            }

            is Component.ShowToast -> {
                val content =
                    ShowToastContent(
                        id = value.id,
                        message = value.message,
                        undoActionId = value.undoActionId,
                        durationMs = value.durationMs,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ShowToast" to inner)))
            }

            is Component.InlineConfirm -> {
                val content =
                    InlineConfirmContent(
                        id = value.id,
                        warning = value.warning,
                        confirmText = value.confirmText,
                        cancelText = value.cancelText,
                        destructive = value.destructive,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("InlineConfirm" to inner)))
            }

            is Component.EditableText -> {
                val content =
                    EditableTextContent(
                        id = value.id,
                        label = value.label,
                        value = value.value,
                        editing = value.editing,
                        validationError = value.validationError,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("EditableText" to inner)))
            }

            is Component.Banner -> {
                val content =
                    BannerContent(
                        text = value.text,
                        actionLabel = value.actionLabel,
                        actionId = value.actionId,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(
                    JsonObject(mapOf("Banner" to inner)),
                )
            }

            is Component.Dropdown -> {
                val content =
                    DropdownContent(
                        id = value.id,
                        label = value.label,
                        selected = value.selected,
                        options = value.options,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("Dropdown" to inner)))
            }

            is Component.AvatarPreview -> {
                val content =
                    AvatarPreviewContent(
                        id = value.id,
                        imageData = value.imageData,
                        initials = value.initials,
                        bgColor = value.bgColor,
                        brightness = value.brightness,
                        editable = value.editable,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("AvatarPreview" to inner)))
            }

            is Component.Slider -> {
                val content =
                    SliderContent(
                        id = value.id,
                        label = value.label,
                        value = value.value,
                        min = value.min,
                        max = value.max,
                        step = value.step,
                        minIcon = value.minIcon,
                        maxIcon = value.maxIcon,
                        a11y = value.a11y,
                    )
                val inner = jsonEncoder.json.encodeToJsonElement(content)
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("Slider" to inner)))
            }

            is Component.Unknown -> {
                // Unknown components should not be serialized back to core
                jsonEncoder.encodeJsonElement(JsonPrimitive("Unknown"))
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
    Password,
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
    val a11y: A11y? = null,
)

@Serializable
data class DropdownOption(
    val id: String,
    val label: String,
)

@Serializable
data class A11y(
    val label: String? = null,
    val hint: String? = null,
)

@Serializable
data class FieldDisplay(
    val id: String,
    @SerialName("field_type") val fieldType: String,
    val label: String,
    val value: String,
    val visibility: UiFieldVisibility,
    val a11y: A11y? = null,
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

                    // Unknown visibility variant — default to Shown
                    else -> UiFieldVisibility.Shown
                }
            }

            is JsonObject -> {
                val groups =
                    element["Groups"]?.jsonArray?.map { it.jsonPrimitive.content }
                if (groups != null) {
                    UiFieldVisibility.Groups(groups)
                } else {
                    // Unknown object variant — default to Shown
                    UiFieldVisibility.Shown
                }
            }

            // Unknown JSON structure — default to Shown
            else -> {
                UiFieldVisibility.Shown
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

@Serializable
data class ContactItem(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    @SerialName("avatar_initials") val avatarInitials: String,
    val status: String? = null,
    val a11y: A11y? = null,
)

@Serializable
data class SettingsItem(
    val id: String,
    val label: String,
    val kind: SettingsItemKind,
    val a11y: A11y? = null,
)

@Serializable(with = SettingsItemKindSerializer::class)
sealed class SettingsItemKind {
    data class Toggle(
        val enabled: Boolean,
    ) : SettingsItemKind()

    data class Value(
        val value: String,
    ) : SettingsItemKind()

    data class Link(
        val detail: String? = null,
    ) : SettingsItemKind()

    data class Destructive(
        val label: String,
    ) : SettingsItemKind()

    /** Unknown settings kind from a newer core version. */
    data object Unknown : SettingsItemKind()
}

internal object SettingsItemKindSerializer : KSerializer<SettingsItemKind> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SettingsItemKind")

    override fun deserialize(decoder: Decoder): SettingsItemKind {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> {
                when {
                    "Toggle" in element -> {
                        val obj = element["Toggle"] as JsonObject
                        SettingsItemKind.Toggle(enabled = obj["enabled"]!!.jsonPrimitive.content.toBooleanStrict())
                    }

                    "Value" in element -> {
                        val obj = element["Value"] as JsonObject
                        SettingsItemKind.Value(value = obj["value"]!!.jsonPrimitive.content)
                    }

                    "Link" in element -> {
                        val obj = element["Link"] as JsonObject
                        SettingsItemKind.Link(detail = obj["detail"]?.jsonPrimitive?.contentOrNull)
                    }

                    "Destructive" in element -> {
                        val obj = element["Destructive"] as JsonObject
                        SettingsItemKind.Destructive(label = obj["label"]!!.jsonPrimitive.content)
                    }

                    else -> {
                        SettingsItemKind.Unknown
                    }
                }
            }

            // Future unit variants (e.g., "Separator") — degrade gracefully
            else -> {
                SettingsItemKind.Unknown
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: SettingsItemKind,
    ) {
        val jsonEncoder = encoder as JsonEncoder
        val element =
            when (value) {
                is SettingsItemKind.Toggle -> {
                    JsonObject(mapOf("Toggle" to JsonObject(mapOf("enabled" to JsonPrimitive(value.enabled)))))
                }

                is SettingsItemKind.Value -> {
                    JsonObject(mapOf("Value" to JsonObject(mapOf("value" to JsonPrimitive(value.value)))))
                }

                is SettingsItemKind.Link -> {
                    JsonObject(
                        mapOf(
                            "Link" to
                                JsonObject(
                                    mapOf(
                                        "detail" to
                                            if (value.detail != null) JsonPrimitive(value.detail) else JsonNull,
                                    ),
                                ),
                        ),
                    )
                }

                is SettingsItemKind.Destructive -> {
                    JsonObject(mapOf("Destructive" to JsonObject(mapOf("label" to JsonPrimitive(value.label)))))
                }

                is SettingsItemKind.Unknown -> {
                    JsonPrimitive("Unknown")
                }
            }
        jsonEncoder.encodeJsonElement(element)
    }
}

@Serializable
data class ActionListItem(
    val id: String,
    val label: String,
    val icon: String? = null,
    val detail: String? = null,
    val a11y: A11y? = null,
)

@Serializable
enum class Status {
    Pending,
    InProgress,
    Success,
    Failed,
    Warning,
}

@Serializable
enum class QrMode {
    Display,
    Scan,
}

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

    data class SearchChanged(
        val componentId: String,
        val query: String,
    ) : UserAction()

    data class ListItemSelected(
        val componentId: String,
        val itemId: String,
    ) : UserAction()

    data class SettingsToggled(
        val componentId: String,
        val itemId: String,
    ) : UserAction()

    data class UndoPressed(
        val actionId: String,
    ) : UserAction()

    data class SliderChanged(
        val componentId: String,
        val valueMilli: Int,
    ) : UserAction()

    /** Unknown action variant from deserialization — should not be sent to core. */
    data object Unknown : UserAction()
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

                is UserAction.SearchChanged -> {
                    JsonObject(
                        mapOf(
                            "SearchChanged" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "query" to JsonPrimitive(value.query),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.ListItemSelected -> {
                    JsonObject(
                        mapOf(
                            "ListItemSelected" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "item_id" to JsonPrimitive(value.itemId),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.SettingsToggled -> {
                    JsonObject(
                        mapOf(
                            "SettingsToggled" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "item_id" to JsonPrimitive(value.itemId),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.UndoPressed -> {
                    JsonObject(
                        mapOf(
                            "UndoPressed" to
                                JsonObject(
                                    mapOf("action_id" to JsonPrimitive(value.actionId)),
                                ),
                        ),
                    )
                }

                is UserAction.SliderChanged -> {
                    JsonObject(
                        mapOf(
                            "SliderChanged" to
                                JsonObject(
                                    mapOf(
                                        "component_id" to JsonPrimitive(value.componentId),
                                        "value_milli" to JsonPrimitive(value.valueMilli),
                                    ),
                                ),
                        ),
                    )
                }

                is UserAction.Unknown -> {
                    // Unknown actions should not be serialized back to core
                    JsonObject(emptyMap())
                }
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): UserAction {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> {
                when {
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

                    "SearchChanged" in element -> {
                        val obj = element["SearchChanged"] as JsonObject
                        UserAction.SearchChanged(
                            componentId = obj["component_id"]!!.jsonPrimitive.content,
                            query = obj["query"]!!.jsonPrimitive.content,
                        )
                    }

                    "ListItemSelected" in element -> {
                        val obj = element["ListItemSelected"] as JsonObject
                        UserAction.ListItemSelected(
                            componentId = obj["component_id"]!!.jsonPrimitive.content,
                            itemId = obj["item_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "SettingsToggled" in element -> {
                        val obj = element["SettingsToggled"] as JsonObject
                        UserAction.SettingsToggled(
                            componentId = obj["component_id"]!!.jsonPrimitive.content,
                            itemId = obj["item_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "UndoPressed" in element -> {
                        val obj = element["UndoPressed"] as JsonObject
                        UserAction.UndoPressed(
                            actionId = obj["action_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "SliderChanged" in element -> {
                        val obj = element["SliderChanged"] as JsonObject
                        UserAction.SliderChanged(
                            componentId = obj["component_id"]!!.jsonPrimitive.content,
                            valueMilli = obj["value_milli"]!!.jsonPrimitive.int,
                        )
                    }

                    else -> {
                        UserAction.Unknown
                    }
                }
            }

            // Future unit variants (e.g., "Heartbeat") — degrade gracefully
            else -> {
                UserAction.Unknown
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

    data object StartDeviceLink : ActionResult()

    data object StartBackupImport : ActionResult()

    data class BackupExportComplete(
        val data: String,
    ) : ActionResult()

    data class OpenContact(
        val contactId: String,
    ) : ActionResult()

    data class OpenUrl(
        val url: String,
    ) : ActionResult()

    data class ShowAlert(
        val title: String,
        val message: String,
    ) : ActionResult()

    data object RequestCamera : ActionResult()

    data class EditContact(
        val contactId: String,
    ) : ActionResult()

    data class OpenEntryDetail(
        val fieldId: String,
    ) : ActionResult()

    data class ShowToast(
        val message: String,
        val undoActionId: String?,
    ) : ActionResult()

    data object WipeComplete : ActionResult()

    data class ExchangeCommands(
        val commands: List<ExchangeCommandDTO>,
    ) : ActionResult()

    data class ShowFormDialog(
        val dialogType: String,
        val contextId: String?,
    ) : ActionResult()

    data class PreviewAs(
        val contactId: String,
    ) : ActionResult()

    data object Unknown : ActionResult()
}

// / DTO for exchange commands from core (ADR-031).
// / Maps to: `vauchi-core::exchange::command::ExchangeCommand`
@Serializable(with = ExchangeCommandDTOSerializer::class)
sealed class ExchangeCommandDTO {
    data class QrDisplay(
        val data: String,
    ) : ExchangeCommandDTO()

    data object QrRequestScan : ExchangeCommandDTO()

    data class BleStartScanning(
        val serviceUuid: String,
    ) : ExchangeCommandDTO()

    data class BleStartAdvertising(
        val serviceUuid: String,
        val payload: List<Int>,
    ) : ExchangeCommandDTO()

    data class BleConnect(
        val deviceId: String,
    ) : ExchangeCommandDTO()

    data class BleWriteCharacteristic(
        val uuid: String,
        val data: List<Int>,
    ) : ExchangeCommandDTO()

    data class BleReadCharacteristic(
        val uuid: String,
    ) : ExchangeCommandDTO()

    data object BleDisconnect : ExchangeCommandDTO()

    data class NfcActivate(
        val payload: List<Int>,
    ) : ExchangeCommandDTO()

    data object NfcDeactivate : ExchangeCommandDTO()

    data class AudioEmitChallenge(
        val data: List<Int>,
    ) : ExchangeCommandDTO()

    data class AudioListenForResponse(
        val timeoutMs: Long,
    ) : ExchangeCommandDTO()

    data object AudioStop : ExchangeCommandDTO()

    data object ImagePickFromLibrary : ExchangeCommandDTO()

    data object ImageCaptureFromCamera : ExchangeCommandDTO()

    data object ImagePickFromFile : ExchangeCommandDTO()

    data object Unknown : ExchangeCommandDTO()
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
                    "StartDeviceLink" -> ActionResult.StartDeviceLink
                    "StartBackupImport" -> ActionResult.StartBackupImport
                    "RequestCamera" -> ActionResult.RequestCamera
                    "WipeComplete" -> ActionResult.WipeComplete
                    else -> ActionResult.Unknown
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

                    "OpenContact" in element -> {
                        val obj = element["OpenContact"] as JsonObject
                        ActionResult.OpenContact(
                            contactId = obj["contact_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "OpenUrl" in element -> {
                        val obj = element["OpenUrl"] as JsonObject
                        ActionResult.OpenUrl(
                            url = obj["url"]!!.jsonPrimitive.content,
                        )
                    }

                    "ShowAlert" in element -> {
                        val obj = element["ShowAlert"] as JsonObject
                        ActionResult.ShowAlert(
                            title = obj["title"]!!.jsonPrimitive.content,
                            message = obj["message"]!!.jsonPrimitive.content,
                        )
                    }

                    "EditContact" in element -> {
                        val obj = element["EditContact"] as JsonObject
                        ActionResult.EditContact(
                            contactId = obj["contact_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "OpenEntryDetail" in element -> {
                        val obj = element["OpenEntryDetail"] as JsonObject
                        ActionResult.OpenEntryDetail(
                            fieldId = obj["field_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "ShowToast" in element -> {
                        val obj = element["ShowToast"] as JsonObject
                        ActionResult.ShowToast(
                            message = obj["message"]!!.jsonPrimitive.content,
                            undoActionId = obj["undo_action_id"]?.jsonPrimitive?.contentOrNull,
                        )
                    }

                    "ExchangeCommands" in element -> {
                        val obj = element["ExchangeCommands"] as JsonObject
                        val cmds =
                            obj["commands"]!!.jsonArray.map { cmdElement ->
                                jsonDecoder.json.decodeFromJsonElement(
                                    ExchangeCommandDTOSerializer,
                                    cmdElement,
                                )
                            }
                        ActionResult.ExchangeCommands(commands = cmds)
                    }

                    "ShowFormDialog" in element -> {
                        val obj = element["ShowFormDialog"] as JsonObject
                        ActionResult.ShowFormDialog(
                            dialogType = obj["dialog_type"]!!.jsonPrimitive.content,
                            contextId = obj["context_id"]?.jsonPrimitive?.contentOrNull,
                        )
                    }

                    "BackupExportComplete" in element -> {
                        val obj = element["BackupExportComplete"] as JsonObject
                        ActionResult.BackupExportComplete(
                            data = obj["data"]!!.jsonPrimitive.content,
                        )
                    }

                    "PreviewAs" in element -> {
                        val obj = element["PreviewAs"] as JsonObject
                        ActionResult.PreviewAs(
                            contactId = obj["contact_id"]!!.jsonPrimitive.content,
                        )
                    }

                    else -> {
                        ActionResult.Unknown
                    }
                }
            }

            else -> {
                ActionResult.Unknown
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

            is ActionResult.StartDeviceLink -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("StartDeviceLink"))
            }

            is ActionResult.StartBackupImport -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("StartBackupImport"))
            }

            is ActionResult.BackupExportComplete -> {
                jsonEncoder.encodeJsonElement(
                    JsonObject(
                        mapOf(
                            "BackupExportComplete" to
                                JsonObject(
                                    mapOf("data" to JsonPrimitive(value.data)),
                                ),
                        ),
                    ),
                )
            }

            is ActionResult.RequestCamera -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("RequestCamera"))
            }

            is ActionResult.WipeComplete -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("WipeComplete"))
            }

            is ActionResult.OpenContact -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "OpenContact" to
                                JsonObject(
                                    mapOf("contact_id" to JsonPrimitive(value.contactId)),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.OpenUrl -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "OpenUrl" to
                                JsonObject(
                                    mapOf("url" to JsonPrimitive(value.url)),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.ShowAlert -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "ShowAlert" to
                                JsonObject(
                                    mapOf(
                                        "title" to JsonPrimitive(value.title),
                                        "message" to JsonPrimitive(value.message),
                                    ),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.EditContact -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "EditContact" to
                                JsonObject(
                                    mapOf("contact_id" to JsonPrimitive(value.contactId)),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.OpenEntryDetail -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "OpenEntryDetail" to
                                JsonObject(
                                    mapOf("field_id" to JsonPrimitive(value.fieldId)),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.ShowToast -> {
                val inner =
                    buildMap<String, JsonElement> {
                        put("message", JsonPrimitive(value.message))
                        put(
                            "undo_action_id",
                            if (value.undoActionId != null) JsonPrimitive(value.undoActionId) else JsonNull,
                        )
                    }
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ShowToast" to JsonObject(inner))))
            }

            is ActionResult.ExchangeCommands -> {
                // Serialization not needed for incoming-only variant
                jsonEncoder.encodeJsonElement(JsonPrimitive("ExchangeCommands"))
            }

            is ActionResult.ShowFormDialog -> {
                val inner =
                    buildMap<String, JsonElement> {
                        put("dialog_type", JsonPrimitive(value.dialogType))
                        put("context_id", if (value.contextId != null) JsonPrimitive(value.contextId) else JsonNull)
                    }
                jsonEncoder.encodeJsonElement(JsonObject(mapOf("ShowFormDialog" to JsonObject(inner))))
            }

            is ActionResult.PreviewAs -> {
                val obj =
                    JsonObject(
                        mapOf(
                            "PreviewAs" to
                                JsonObject(
                                    mapOf("contact_id" to JsonPrimitive(value.contactId)),
                                ),
                        ),
                    )
                jsonEncoder.encodeJsonElement(obj)
            }

            is ActionResult.Unknown -> {
                jsonEncoder.encodeJsonElement(JsonPrimitive("Unknown"))
            }
        }
    }
}

internal object ExchangeCommandDTOSerializer : KSerializer<ExchangeCommandDTO> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ExchangeCommandDTO")

    override fun deserialize(decoder: Decoder): ExchangeCommandDTO {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                when (element.content) {
                    "QrRequestScan" -> ExchangeCommandDTO.QrRequestScan
                    "BleDisconnect" -> ExchangeCommandDTO.BleDisconnect
                    "NfcDeactivate" -> ExchangeCommandDTO.NfcDeactivate
                    "AudioStop" -> ExchangeCommandDTO.AudioStop
                    "ImagePickFromLibrary" -> ExchangeCommandDTO.ImagePickFromLibrary
                    "ImageCaptureFromCamera" -> ExchangeCommandDTO.ImageCaptureFromCamera
                    "ImagePickFromFile" -> ExchangeCommandDTO.ImagePickFromFile
                    else -> ExchangeCommandDTO.Unknown
                }
            }

            is JsonObject -> {
                when {
                    "QrDisplay" in element -> {
                        val obj = element["QrDisplay"] as JsonObject
                        ExchangeCommandDTO.QrDisplay(
                            data = obj["data"]!!.jsonPrimitive.content,
                        )
                    }

                    "BleStartScanning" in element -> {
                        val obj = element["BleStartScanning"] as JsonObject
                        ExchangeCommandDTO.BleStartScanning(
                            serviceUuid = obj["service_uuid"]!!.jsonPrimitive.content,
                        )
                    }

                    "BleStartAdvertising" in element -> {
                        val obj = element["BleStartAdvertising"] as JsonObject
                        ExchangeCommandDTO.BleStartAdvertising(
                            serviceUuid = obj["service_uuid"]!!.jsonPrimitive.content,
                            payload = obj["payload"]!!.jsonArray.map { it.jsonPrimitive.int },
                        )
                    }

                    "BleConnect" in element -> {
                        val obj = element["BleConnect"] as JsonObject
                        ExchangeCommandDTO.BleConnect(
                            deviceId = obj["device_id"]!!.jsonPrimitive.content,
                        )
                    }

                    "BleWriteCharacteristic" in element -> {
                        val obj = element["BleWriteCharacteristic"] as JsonObject
                        ExchangeCommandDTO.BleWriteCharacteristic(
                            uuid = obj["uuid"]!!.jsonPrimitive.content,
                            data = obj["data"]!!.jsonArray.map { it.jsonPrimitive.int },
                        )
                    }

                    "BleReadCharacteristic" in element -> {
                        val obj = element["BleReadCharacteristic"] as JsonObject
                        ExchangeCommandDTO.BleReadCharacteristic(
                            uuid = obj["uuid"]!!.jsonPrimitive.content,
                        )
                    }

                    "NfcActivate" in element -> {
                        val obj = element["NfcActivate"] as JsonObject
                        ExchangeCommandDTO.NfcActivate(
                            payload = obj["payload"]!!.jsonArray.map { it.jsonPrimitive.int },
                        )
                    }

                    "AudioEmitChallenge" in element -> {
                        val obj = element["AudioEmitChallenge"] as JsonObject
                        ExchangeCommandDTO.AudioEmitChallenge(
                            data = obj["data"]!!.jsonArray.map { it.jsonPrimitive.int },
                        )
                    }

                    "AudioListenForResponse" in element -> {
                        val obj = element["AudioListenForResponse"] as JsonObject
                        ExchangeCommandDTO.AudioListenForResponse(
                            timeoutMs = obj["timeout_ms"]!!.jsonPrimitive.long,
                        )
                    }

                    else -> {
                        ExchangeCommandDTO.Unknown
                    }
                }
            }

            else -> {
                ExchangeCommandDTO.Unknown
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: ExchangeCommandDTO,
    ) {
        // Exchange commands are incoming-only — serialization not needed
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(JsonPrimitive("ExchangeCommandDTO"))
    }
}

// ── OnboardingData ──────────────────────────────────────────────────

@Serializable
data class OnboardingData(
    @SerialName("display_name") val displayName: String,
)
