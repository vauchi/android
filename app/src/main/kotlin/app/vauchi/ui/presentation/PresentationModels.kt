// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.JsonElement

data class AccessibilitySpec(
    val label: String,
    val description: String?,
)

enum class ActionTone {
    Standard,
    Destructive,
}

data class ActionSpec(
    val interactionId: String,
    val label: String,
    val accessibilityLabel: String,
    val iconToken: String?,
    val enabled: Boolean,
    val tone: ActionTone,
    val shortcut: String?,
)

data class ContextBar(
    val back: ActionSpec?,
    val navigation: ActionSpec?,
    val primary: ActionSpec?,
    val secondary: ActionSpec?,
)

enum class OverlayKind {
    Navigation,
    ActionMenu,
}

data class OverlaySpec(
    val kind: OverlayKind,
    val title: String?,
    val items: List<ActionSpec>,
)

enum class WindowClass {
    Compact,
    Medium,
    Expanded,
}

enum class PaneLayout {
    Single,
    Split,
}

data class PresentationProfile(
    val windowClass: WindowClass,
    val paneLayout: PaneLayout,
    val primarySurface: String,
    val detailSurface: String?,
    val activeSurface: String,
)

data class PresentationTokens(
    val spacingSmall: Int,
    val spacingMedium: Int,
    val spacingLarge: Int,
    val cornerRadius: Int,
    val minimumTargetSize: Int,
)

data class ChoiceOption(
    val id: String,
    val label: String,
)

data class PresentationRow(
    val title: String,
    val subtitle: String?,
    val detail: String?,
    val iconToken: String?,
    val imageData: List<Int>?,
    val fallbackText: String?,
    val selected: Boolean,
    val enabled: Boolean,
    val activation: ActionSpec?,
    val secondaryActions: List<ActionSpec>,
    val controls: List<PresentationNode>,
    val accessibility: AccessibilitySpec,
)

sealed interface PresentationNode {
    data class Text(
        val id: String?,
        val content: String,
        val style: TextRole,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Input(
        val bindingId: String,
        val label: String,
        val value: String,
        val placeholder: String?,
        val inputKind: String,
        val maxLength: Int?,
        val validationError: String?,
        val enabled: Boolean,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Toggle(
        val bindingId: String,
        val label: String,
        val value: Boolean,
        val enabled: Boolean,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Choice(
        val bindingId: String,
        val label: String,
        val selected: String?,
        val options: List<ChoiceOption>,
        val enabled: Boolean,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Group(
        val id: String?,
        val label: String?,
        val horizontal: Boolean,
        val children: List<PresentationNode>,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class ListNode(
        val id: String,
        val label: String?,
        val rows: List<PresentationRow>,
        val searchable: Boolean,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Image(
        val id: String?,
        val data: List<Int>?,
        val fallbackText: String?,
        val circular: Boolean,
        val brightness: Double,
        val activation: ActionSpec?,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Status(
        val id: String?,
        val title: String,
        val detail: String?,
        val iconToken: String?,
        val badge: String?,
        val tone: String,
        val activation: ActionSpec?,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Qr(
        val id: String,
        val payloads: List<String>,
        val capture: Boolean,
        val label: String?,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Confirmation(
        val id: String,
        val warning: String,
        val confirm: ActionSpec,
        val cancel: ActionSpec,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Slider(
        val bindingId: String,
        val label: String,
        val value: Double,
        val minimum: Double,
        val maximum: Double,
        val step: Double?,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data class Progress(
        val label: String?,
        val value: Double?,
        val accessibility: AccessibilitySpec,
    ) : PresentationNode

    data object Divider : PresentationNode
}

data class SurfaceSpec(
    val surfaceId: String,
    val revision: ULong,
    val title: String,
    val subtitle: String?,
    val accessibilityLabel: String,
    val layout: String,
    val tokens: PresentationTokens,
    val nodes: List<PresentationNode>,
)

sealed interface PresentationCommand {
    data class ReplaceSurface(
        val surface: SurfaceSpec,
    ) : PresentationCommand

    data class SetContextBar(
        val surfaceId: String,
        val revision: ULong,
        val bar: ContextBar,
    ) : PresentationCommand

    data class PresentOverlay(
        val surfaceId: String,
        val revision: ULong,
        val overlay: OverlaySpec,
    ) : PresentationCommand

    data class DismissOverlay(
        val surfaceId: String,
        val revision: ULong,
        val kind: OverlayKind,
    ) : PresentationCommand

    data class SetProfile(
        val profile: PresentationProfile,
    ) : PresentationCommand

    data class Effect(
        val variant: String,
        val payload: JsonElement,
    ) : PresentationCommand
}

data class PresentationEnvelope(
    val commands: List<PresentationCommand>,
)

data class RevisionedBar(
    val revision: ULong,
    val bar: ContextBar,
)

data class RevisionedOverlay(
    val surfaceId: String,
    val revision: ULong,
    val overlay: OverlaySpec,
)

data class PresentationState(
    val surfaces: Map<String, SurfaceSpec> = emptyMap(),
    val bars: Map<String, RevisionedBar> = emptyMap(),
    val profile: PresentationProfile? = null,
    val overlay: RevisionedOverlay? = null,
) {
    val activeSurfaceId: String?
        get() = profile?.activeSurface ?: surfaces.keys.sorted().firstOrNull()

    val activeBar: ContextBar?
        get() = activeSurfaceId?.let(bars::get)?.bar

    /**
     * The overlay, but only while the surface it was raised over is still the
     * active one at the revision it was raised at.
     *
     * Core clears its own open-overlay state on every dispatch, so it expects
     * an overlay to die with its surface and sends no dismissal when an item
     * inside it navigates. Rendering [overlay] unscoped left the menu on
     * screen after a selection, and the next menu tap re-presented it instead
     * of toggling it shut.
     */
    val activeOverlay: RevisionedOverlay?
        get() =
            overlay?.takeIf {
                it.surfaceId == activeSurfaceId &&
                    surfaces[it.surfaceId]?.revision == it.revision
            }

    val visibleSurfaceIds: List<String>
        get() {
            val value = profile ?: return activeSurfaceId?.let(::listOf) ?: emptyList()
            return if (value.paneLayout == PaneLayout.Split) {
                listOfNotNull(value.primarySurface, value.detailSurface)
            } else {
                listOf(value.activeSurface)
            }
        }
}

class PresentationProtocolException(
    message: String,
) : Exception(message)

data class ApplyPresentationResult(
    val state: PresentationState,
    val effects: List<PresentationCommand.Effect>,
)
