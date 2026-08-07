// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresentationProtocolTest {
    @Test
    fun `prepared commands apply atomically`() {
        val commands =
            PresentationProtocol
                .decodeEnvelope(
                    envelope(
                        replaceSurface(1),
                        contextBar(1, "Save"),
                        profile("compact", "single"),
                    ),
                ).commands

        val result = PresentationReducer.apply(PresentationState(), commands)

        assertEquals(1uL, result.state.surfaces["main"]?.revision)
        assertEquals(
            "Save",
            result.state.bars["main"]
                ?.bar
                ?.primary
                ?.label,
        )
        assertEquals("main", result.state.profile?.activeSurface)
        assertEquals(emptyList(), result.effects)
    }

    // @scenario: generic_presentation_protocol.feature :: Every shell renders the same prepared presentation
    @Test
    fun `surface remains active while profile is pending`() {
        val result =
            PresentationReducer.apply(
                PresentationState(),
                PresentationProtocol
                    .decodeEnvelope(envelope(replaceSurface(1)))
                    .commands,
            )

        assertEquals("main", result.state.activeSurfaceId)
        assertEquals(listOf("main"), result.state.visibleSurfaceIds)
    }

    @Test
    fun `re-emitting the same revision re-applies instead of failing`() {
        // Core's revision advances only on user actions, so racing full
        // rebuilds re-emit the same surface at the same revision. iOS has
        // always treated that as a legitimate re-apply
        // (PresentationState.swift); Android rejected it, which failed
        // every cold launch with "stale surface revision for my_info".
        val current =
            PresentationReducer
                .apply(
                    PresentationState(),
                    PresentationProtocol
                        .decodeEnvelope(
                            envelope(replaceSurface(2)),
                        ).commands,
                ).state

        val result =
            PresentationReducer.apply(
                current,
                PresentationProtocol
                    .decodeEnvelope(
                        envelope(replaceSurface(2)),
                    ).commands,
            )

        assertEquals(2uL, result.state.surfaces["main"]?.revision)
        assertEquals(emptyList(), result.effects)
    }

    @Test
    fun `stale transaction leaves prior state untouched`() {
        val current =
            PresentationReducer
                .apply(
                    PresentationState(),
                    PresentationProtocol
                        .decodeEnvelope(
                            envelope(replaceSurface(2)),
                        ).commands,
                ).state

        assertFailsWith<PresentationProtocolException> {
            PresentationReducer.apply(
                current,
                PresentationProtocol
                    .decodeEnvelope(
                        envelope(
                            replaceSurface(1),
                            contextBar(1, "Stale"),
                        ),
                    ).commands,
            )
        }

        assertEquals(2uL, current.surfaces["main"]?.revision)
        assertNull(current.bars["main"])
    }

    @Test
    fun `navigation and action overlays remain distinct`() {
        val navigation = PresentationProtocol.decodeOverlay("""{"kind":"navigation","title":null,"items":[]}""")
        val actions = PresentationProtocol.decodeOverlay("""{"kind":"action_menu","title":null,"items":[]}""")

        assertEquals(OverlayKind.Navigation, navigation.kind)
        assertEquals(OverlayKind.ActionMenu, actions.kind)
        assertEquals(
            OverlayTransitionIdentity.NavigationReduced,
            overlayTransitionIdentity(OverlayKind.Navigation, reducedMotion = true),
        )
        assertEquals(
            OverlayTransitionIdentity.ActionReduced,
            overlayTransitionIdentity(OverlayKind.ActionMenu, reducedMotion = true),
        )
        assertTrue(
            overlayTransitionIdentity(OverlayKind.Navigation, reducedMotion = false) !=
                overlayTransitionIdentity(OverlayKind.ActionMenu, reducedMotion = false),
        )
    }

    @Test
    fun `environment event preserves raw viewport`() {
        val event =
            PresentationEvent.environmentChanged(
                width = 600,
                height = 900,
                inputModes = listOf(InputMode.Touch, InputMode.Keyboard),
                reducedMotion = true,
            )
        val payload = Json.parseToJsonElement(event.toJson()).jsonObject
        val environment = payload.getValue("PresentationEnvironmentChanged").jsonObject

        assertEquals("600", environment.getValue("available_width").toString())
        assertEquals("900", environment.getValue("available_height").toString())
        assertEquals("\"reduced\"", environment.getValue("motion").toString())
    }

    // @scenario: generic_presentation_protocol.feature :: User interaction returns as an opaque event
    @Test
    fun `presentation invalidation is a canonical unit event`() {
        assertEquals(
            "\"PresentationInvalidated\"",
            PresentationEvent.presentationInvalidated.toJson(),
        )
    }

    @Test
    fun `native shortcut policy resolves contextual roles and causal undo`() {
        val undo =
            ActionSpec(
                interactionId = "undo.archive",
                label = "Undo archive",
                accessibilityLabel = "Undo archive",
                iconToken = null,
                enabled = true,
                tone = ActionTone.Standard,
                shortcut = "undo",
            )
        val bar =
            ContextBar(
                back = action("back"),
                navigation = action("navigate"),
                primary = undo,
                secondary = action("more"),
            )

        assertEquals("back", contextualShortcut(bar, ShortcutGesture.Back)?.interactionId)
        assertEquals("navigate", contextualShortcut(bar, ShortcutGesture.Navigation)?.interactionId)
        assertEquals("undo.archive", contextualShortcut(bar, ShortcutGesture.Undo)?.interactionId)
        assertEquals("more", contextualShortcut(bar, ShortcutGesture.Secondary)?.interactionId)
        assertNull(contextualShortcut(bar, ShortcutGesture.Primary))
    }

    @Test
    fun `focus memory survives layout replacement until the same binding returns`() {
        val focused = rememberFocusedBinding(current = null, bindingId = "display_name", focused = true)

        assertEquals("display_name", focused)
        assertTrue(shouldRestoreFocus(focused, "display_name"))
        assertEquals(
            focused,
            rememberFocusedBinding(
                current = focused,
                bindingId = "display_name",
                focused = false,
            ),
        )
        assertTrue(!shouldRestoreFocus(focused, "email"))

        val nextFocused =
            rememberFocusedBinding(
                current = focused,
                bindingId = "email",
                focused = true,
            )
        assertEquals(
            nextFocused,
            rememberFocusedBinding(
                current = nextFocused,
                bindingId = "display_name",
                focused = false,
            ),
        )
    }

    // Core makes the context-bar menu buttons toggle by rewriting a repeat
    // PresentOverlay into DismissOverlay. Without a shell mapping the command
    // fell through to Effect and the menu stayed open on every second tap —
    // observed on a Pixel 3a against a core carrying the toggle.
    @Test
    fun `dismiss overlay closes the open overlay`() {
        val opened =
            PresentationReducer
                .apply(
                    PresentationState(),
                    PresentationProtocol
                        .decodeEnvelope(
                            envelope(replaceSurface(1), presentOverlay(1, "navigation")),
                        ).commands,
                ).state
        assertEquals(OverlayKind.Navigation, opened.overlay?.overlay?.kind)

        val result =
            PresentationReducer.apply(
                opened,
                PresentationProtocol
                    .decodeEnvelope(envelope(dismissOverlay(1, "navigation")))
                    .commands,
            )

        assertNull(result.state.overlay, "a dismissed overlay must leave no overlay state")
        assertEquals(emptyList(), result.effects, "DismissOverlay must not fall through to Effect")
    }

    private fun envelope(vararg commands: String): String = """{"commands":[${commands.joinToString(",")}]}"""

    private fun presentOverlay(
        revision: Int,
        kind: String,
    ): String =
        """
        {"PresentOverlay":{
          "surface_id":"main",
          "revision":$revision,
          "overlay":{"kind":"$kind","title":"More","items":[]}
        }}
        """.trimIndent()

    private fun dismissOverlay(
        revision: Int,
        kind: String,
    ): String =
        """
        {"DismissOverlay":{
          "surface_id":"main",
          "revision":$revision,
          "kind":"$kind"
        }}
        """.trimIndent()

    private fun replaceSurface(revision: Int): String =
        """
        {"ReplaceSurface":{"surface":{
          "surface_id":"main",
          "revision":$revision,
          "title":"Prepared by Core",
          "subtitle":null,
          "accessibility_label":"Prepared by Core",
          "layout":"scroll",
          "tokens":{
            "spacing_small":4,
            "spacing_medium":8,
            "spacing_large":16,
            "corner_radius":8,
            "minimum_target_size":44
          },
          "nodes":[]
        }}}
        """.trimIndent()

    private fun contextBar(
        revision: Int,
        label: String,
    ): String =
        """
        {"SetContextBar":{
          "surface_id":"main",
          "revision":$revision,
          "bar":{
            "back":null,
            "navigation":null,
            "primary":{
              "interaction_id":"save",
              "label":"$label",
              "accessibility_label":"$label",
              "icon_token":null,
              "enabled":true,
              "shortcut":null
            },
            "secondary":null
          }
        }}
        """.trimIndent()

    private fun action(id: String): ActionSpec =
        ActionSpec(
            interactionId = id,
            label = id,
            accessibilityLabel = id,
            iconToken = null,
            enabled = true,
            tone = ActionTone.Standard,
            shortcut = null,
        )

    private fun profile(
        windowClass: String,
        paneLayout: String,
    ): String =
        """
        {"SetPresentationProfile":{"profile":{
          "window_class":"$windowClass",
          "pane_layout":"$paneLayout",
          "primary_surface":"main",
          "detail_surface":null,
          "active_surface":"main"
        }}}
        """.trimIndent()
}
