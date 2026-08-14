// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class InputMode(
    val wire: String,
) {
    Touch("touch"),
    Pointer("pointer"),
    Keyboard("keyboard"),
}

sealed interface PresentationEvent {
    fun toJson(): String

    data class SurfaceActivated(
        val surfaceId: String,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("SurfaceActivated") {
                put("surface_id", surfaceId)
            }
    }

    data class ActionActivated(
        val surfaceId: String,
        val interactionId: String,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("ActionActivated") {
                put("surface_id", surfaceId)
                put("interaction_id", interactionId)
            }
    }

    data class ValueChanged(
        val surfaceId: String,
        val bindingId: String,
        val value: JsonElement,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("ValueChanged") {
                put("surface_id", surfaceId)
                put("binding_id", bindingId)
                put("value", value)
            }
    }

    /**
     * The user pressed the keyboard's own "done" action in a field. Core
     * decides whether that means anything on this surface; a field whose
     * screen does not care simply produces no visible change.
     */
    data class InputSubmitted(
        val surfaceId: String,
        val bindingId: String,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("InputSubmitted") {
                put("surface_id", surfaceId)
                put("binding_id", bindingId)
            }
    }

    /**
     * A field lost focus without the user having submitted. Reported so
     * Core can offer a way to commit text the user left behind rather
     * than committing it on their behalf.
     */
    data class InputFocusEnded(
        val surfaceId: String,
        val bindingId: String,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("InputFocusEnded") {
                put("surface_id", surfaceId)
                put("binding_id", bindingId)
            }
    }

    data class BackRequested(
        val surfaceId: String,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("BackRequested") {
                put("surface_id", surfaceId)
            }
    }

    data class OverlayDismissed(
        val surfaceId: String,
        val kind: OverlayKind,
    ) : PresentationEvent {
        override fun toJson(): String =
            variant("OverlayDismissed") {
                put("surface_id", surfaceId)
                put(
                    "kind",
                    if (kind == OverlayKind.Navigation) {
                        "navigation"
                    } else {
                        "action_menu"
                    },
                )
            }
    }

    data class Raw(
        private val value: String,
    ) : PresentationEvent {
        override fun toJson(): String = value
    }

    companion object {
        fun textValue(
            surfaceId: String,
            bindingId: String,
            value: String,
        ): PresentationEvent =
            ValueChanged(
                surfaceId,
                bindingId,
                buildJsonObject { put("text", value) },
            )

        fun booleanValue(
            surfaceId: String,
            bindingId: String,
            value: Boolean,
        ): PresentationEvent =
            ValueChanged(
                surfaceId,
                bindingId,
                buildJsonObject { put("boolean", value) },
            )

        fun choiceValue(
            surfaceId: String,
            bindingId: String,
            value: String?,
        ): PresentationEvent =
            ValueChanged(
                surfaceId,
                bindingId,
                buildJsonObject {
                    put("choice", value?.let(::JsonPrimitive) ?: JsonNull)
                },
            )

        fun numberValue(
            surfaceId: String,
            bindingId: String,
            value: Double,
        ): PresentationEvent =
            ValueChanged(
                surfaceId,
                bindingId,
                buildJsonObject { put("number", value) },
            )

        fun environmentChanged(
            width: Int,
            height: Int,
            inputModes: List<InputMode>,
            reducedMotion: Boolean,
        ): PresentationEvent =
            Raw(
                variant("PresentationEnvironmentChanged") {
                    put("available_width", width.coerceAtLeast(0))
                    put("available_height", height.coerceAtLeast(0))
                    put(
                        "input_modes",
                        buildJsonArray {
                            inputModes.forEach {
                                add(JsonPrimitive(it.wire))
                            }
                        },
                    )
                    put("motion", if (reducedMotion) "reduced" else "full")
                },
            )

        fun deepLinkOpened(uri: String): PresentationEvent = Raw(variant("DeepLinkOpened") { put("uri", uri) })

        val appBackgrounded: PresentationEvent = Raw("\"AppBackgrounded\"")
        val presentationInvalidated: PresentationEvent = Raw("\"PresentationInvalidated\"")
    }
}

private fun variant(
    name: String,
    content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): String =
    buildJsonObject {
        put(name, buildJsonObject(content))
    }.toString()
