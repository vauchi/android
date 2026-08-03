// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object PresentationProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeEnvelope(value: String): PresentationEnvelope {
        val root = json.parseToJsonElement(value).jsonObject
        return PresentationEnvelope(
            root.array("commands").map(::decodeCommand),
        )
    }

    fun decodeOverlay(value: String): OverlaySpec = overlay(json.parseToJsonElement(value).jsonObject)

    private fun decodeCommand(element: JsonElement): PresentationCommand {
        if (element is JsonPrimitive && element.isString) {
            return PresentationCommand.Effect(element.content, element)
        }
        val objectValue = element.jsonObject
        val variant =
            objectValue.keys.singleOrNull()
                ?: throw PresentationProtocolException("command must have one variant")
        val payload = objectValue.getValue(variant)
        val body = payload.jsonObject
        return when (variant) {
            "ReplaceSurface" -> {
                PresentationCommand.ReplaceSurface(
                    surface(body.objectValue("surface")),
                )
            }

            "SetContextBar" -> {
                PresentationCommand.SetContextBar(
                    surfaceId = body.string("surface_id"),
                    revision = body.ulong("revision"),
                    bar = contextBar(body.objectValue("bar")),
                )
            }

            "PresentOverlay" -> {
                PresentationCommand.PresentOverlay(
                    surfaceId = body.string("surface_id"),
                    revision = body.ulong("revision"),
                    overlay = overlay(body.objectValue("overlay")),
                )
            }

            "SetPresentationProfile" -> {
                PresentationCommand.SetProfile(
                    profile(body.objectValue("profile")),
                )
            }

            else -> {
                PresentationCommand.Effect(variant, payload)
            }
        }
    }

    private fun surface(value: JsonObject): SurfaceSpec =
        SurfaceSpec(
            surfaceId = value.string("surface_id"),
            revision = value.ulong("revision"),
            title = value.string("title"),
            subtitle = value.nullableString("subtitle"),
            accessibilityLabel = value.string("accessibility_label"),
            layout = value.string("layout"),
            tokens = tokens(value.objectValue("tokens")),
            nodes = value.array("nodes").map(::node),
        )

    private fun tokens(value: JsonObject): PresentationTokens =
        PresentationTokens(
            spacingSmall = value.int("spacing_small"),
            spacingMedium = value.int("spacing_medium"),
            spacingLarge = value.int("spacing_large"),
            cornerRadius = value.int("corner_radius"),
            minimumTargetSize = value.int("minimum_target_size"),
        )

    private fun contextBar(value: JsonObject): ContextBar =
        ContextBar(
            back = value.nullableObject("back")?.let(::action),
            navigation = value.nullableObject("navigation")?.let(::action),
            primary = value.nullableObject("primary")?.let(::action),
            secondary = value.nullableObject("secondary")?.let(::action),
        )

    private fun action(value: JsonObject): ActionSpec =
        ActionSpec(
            interactionId = value.string("interaction_id"),
            label = value.string("label"),
            accessibilityLabel = value.string("accessibility_label"),
            iconToken = value.nullableString("icon_token"),
            enabled = value.boolean("enabled"),
            tone =
                if (value.nullableString("tone") == "destructive") {
                    ActionTone.Destructive
                } else {
                    ActionTone.Standard
                },
            shortcut = value.nullableString("shortcut"),
        )

    private fun overlay(value: JsonObject): OverlaySpec =
        OverlaySpec(
            kind =
                when (value.string("kind")) {
                    "navigation" -> OverlayKind.Navigation
                    "action_menu" -> OverlayKind.ActionMenu
                    else -> throw PresentationProtocolException("unknown overlay kind")
                },
            title = value.nullableString("title"),
            items = value.array("items").map { action(it.jsonObject) },
        )

    private fun profile(value: JsonObject): PresentationProfile =
        PresentationProfile(
            windowClass =
                when (value.string("window_class")) {
                    "compact" -> WindowClass.Compact
                    "medium" -> WindowClass.Medium
                    "expanded" -> WindowClass.Expanded
                    else -> throw PresentationProtocolException("unknown window class")
                },
            paneLayout =
                when (value.string("pane_layout")) {
                    "single" -> PaneLayout.Single
                    "split" -> PaneLayout.Split
                    else -> throw PresentationProtocolException("unknown pane layout")
                },
            primarySurface = value.string("primary_surface"),
            detailSurface = value.nullableString("detail_surface"),
            activeSurface = value.string("active_surface"),
        )

    private fun node(element: JsonElement): PresentationNode {
        if (element is JsonPrimitive && element.content == "Divider") {
            return PresentationNode.Divider
        }
        val tagged = element.jsonObject
        val variant =
            tagged.keys.singleOrNull()
                ?: throw PresentationProtocolException("node must have one variant")
        val value = tagged.getValue(variant).jsonObject
        return when (variant) {
            "Text" -> {
                PresentationNode.Text(
                    value.nullableString("id"),
                    value.string("content"),
                    textRoleOrBody(value.string("style")),
                    accessibility(value),
                )
            }

            "Input" -> {
                PresentationNode.Input(
                    value.string("binding_id"),
                    value.string("label"),
                    value.string("value"),
                    value.nullableString("placeholder"),
                    value.string("input_kind"),
                    value.nullableInt("max_length"),
                    value.nullableString("validation_error"),
                    value.boolean("enabled"),
                    accessibility(value),
                )
            }

            "Toggle" -> {
                PresentationNode.Toggle(
                    value.string("binding_id"),
                    value.string("label"),
                    value.boolean("value"),
                    value.boolean("enabled"),
                    accessibility(value),
                )
            }

            "Choice" -> {
                choice(value)
            }

            "Group" -> {
                group(value)
            }

            "List" -> {
                listNode(value)
            }

            "Image" -> {
                image(value)
            }

            "Status" -> {
                status(value)
            }

            "Qr" -> {
                qr(value)
            }

            "Confirmation" -> {
                PresentationNode.Confirmation(
                    value.string("id"),
                    value.string("warning"),
                    action(value.objectValue("confirm")),
                    action(value.objectValue("cancel")),
                    accessibility(value),
                )
            }

            "Slider" -> {
                PresentationNode.Slider(
                    value.string("binding_id"),
                    value.string("label"),
                    value.double("value"),
                    value.double("minimum"),
                    value.double("maximum"),
                    value.nullableDouble("step"),
                    accessibility(value),
                )
            }

            "Progress" -> {
                PresentationNode.Progress(
                    value.nullableString("label"),
                    value.nullableDouble("value"),
                    accessibility(value),
                )
            }

            else -> {
                throw PresentationProtocolException("unknown node $variant")
            }
        }
    }

    private fun choice(value: JsonObject): PresentationNode.Choice =
        PresentationNode.Choice(
            bindingId = value.string("binding_id"),
            label = value.string("label"),
            selected = value.nullableString("selected"),
            options =
                value.array("options").map {
                    val option = it.jsonObject
                    ChoiceOption(option.string("id"), option.string("label"))
                },
            enabled = value.boolean("enabled"),
            accessibility = accessibility(value),
        )

    private fun group(value: JsonObject): PresentationNode.Group =
        PresentationNode.Group(
            id = value.nullableString("id"),
            label = value.nullableString("label"),
            horizontal = value.string("axis") == "horizontal",
            children = value.array("children").map(::node),
            accessibility = accessibility(value),
        )

    private fun listNode(value: JsonObject): PresentationNode.ListNode =
        PresentationNode.ListNode(
            id = value.string("id"),
            label = value.nullableString("label"),
            rows = value.array("rows").map { row(it.jsonObject) },
            searchable = value.boolean("searchable"),
            accessibility = accessibility(value),
        )

    private fun row(value: JsonObject): PresentationRow =
        PresentationRow(
            title = value.string("title"),
            subtitle = value.nullableString("subtitle"),
            detail = value.nullableString("detail"),
            iconToken = value.nullableString("icon_token"),
            imageData = value.nullableArray("image_data")?.map { it.jsonPrimitive.int },
            fallbackText = value.nullableString("fallback_text"),
            selected = value.boolean("selected"),
            enabled = value.boolean("enabled"),
            activation = value.nullableObject("activation")?.let(::action),
            secondaryActions =
                value.array("secondary_actions").map { action(it.jsonObject) },
            controls = value.array("controls").map(::node),
            accessibility = accessibility(value),
        )

    private fun image(value: JsonObject): PresentationNode.Image =
        PresentationNode.Image(
            id = value.nullableString("id"),
            data = value.nullableArray("data")?.map { it.jsonPrimitive.int },
            fallbackText = value.nullableString("fallback_text"),
            circular = value.string("shape") == "circle",
            brightness = value.double("brightness"),
            activation = value.nullableObject("activation")?.let(::action),
            accessibility = accessibility(value),
        )

    private fun status(value: JsonObject): PresentationNode.Status =
        PresentationNode.Status(
            id = value.nullableString("id"),
            title = value.string("title"),
            detail = value.nullableString("detail"),
            iconToken = value.nullableString("icon_token"),
            badge = value.nullableString("badge"),
            tone = value.string("tone"),
            activation = value.nullableObject("activation")?.let(::action),
            accessibility = accessibility(value),
        )

    private fun qr(value: JsonObject): PresentationNode.Qr =
        PresentationNode.Qr(
            id = value.string("id"),
            payloads = value.array("payloads").map { it.jsonPrimitive.content },
            capture = value.string("purpose") == "capture",
            label = value.nullableString("label"),
            accessibility = accessibility(value),
        )

    // An unknown style means Core and shell disagree about the protocol.
    // Render the text as body rather than dropping the surface — losing a
    // whole screen over a font distinction is disproportionate.
    //
    // KNOWN GAP: this fallback is silent at runtime. Logging it would need
    // `android.util.Log` here, and this parser is deliberately free of
    // Android imports so it stays runnable as a plain JVM unit test (the
    // project sets no `unitTests.returnDefaultValues`, so an Android call
    // would throw "not mocked"). Emitting the diagnostic properly needs a
    // seam threaded from `decodeEnvelope` to the single caller in
    // `CoreAppViewModel`. Covered by test, not by telemetry: see
    // `TextRoleTest.an unknown text style still renders its text as body`.
    private fun textRoleOrBody(wire: String): TextRole = parseTextRole(wire) ?: TextRole.Body

    private fun accessibility(value: JsonObject): AccessibilitySpec {
        val item = value.objectValue("accessibility")
        return AccessibilitySpec(
            label = item.string("label"),
            description = item.nullableString("description"),
        )
    }
}

private fun JsonObject.element(key: String): JsonElement = this[key] ?: throw PresentationProtocolException("missing $key")

private fun JsonObject.string(key: String): String = element(key).jsonPrimitive.content

private fun JsonObject.nullableString(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.boolean(key: String): Boolean = element(key).jsonPrimitive.boolean

private fun JsonObject.int(key: String): Int = element(key).jsonPrimitive.int

private fun JsonObject.ulong(key: String): ULong = element(key).jsonPrimitive.long.toULong()

private fun JsonObject.double(key: String): Double = element(key).jsonPrimitive.double

private fun JsonObject.nullableInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int

private fun JsonObject.nullableDouble(key: String): Double? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double

private fun JsonObject.objectValue(key: String): JsonObject = element(key).jsonObject

private fun JsonObject.nullableObject(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull }?.jsonObject

private fun JsonObject.array(key: String): JsonArray = element(key).jsonArray

private fun JsonObject.nullableArray(key: String): JsonArray? = this[key]?.takeUnless { it is JsonNull }?.jsonArray
