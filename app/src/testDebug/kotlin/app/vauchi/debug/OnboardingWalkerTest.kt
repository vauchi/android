// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.debug

import app.vauchi.ui.presentation.AccessibilitySpec
import app.vauchi.ui.presentation.ActionSpec
import app.vauchi.ui.presentation.ActionTone
import app.vauchi.ui.presentation.ContextBar
import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationNode
import app.vauchi.ui.presentation.PresentationRow
import app.vauchi.ui.presentation.PresentationState
import app.vauchi.ui.presentation.PresentationTokens
import app.vauchi.ui.presentation.RevisionedBar
import app.vauchi.ui.presentation.SurfaceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The walker replays a user's onboarding against whatever Core rendered;
 * every id it dispatches must come from that surface, so the fixtures
 * mint their own ids and the assertions check they are echoed back.
 */
class OnboardingWalkerTest {
    @Test
    fun presses_the_enabled_primary_action_before_anything_else() {
        val state =
            state(
                nodes = listOf(input("name_field", value = "")),
                primary = action("go", enabled = true),
            )

        val step = OnboardingWalker.nextStep(state)

        assertEquals(PresentationEvent.ActionActivated("s", "go"), step?.event)
        assertEquals(7uL, step?.revision)
    }

    @Test
    fun fills_the_first_empty_input_when_the_primary_is_disabled() {
        val state =
            state(
                nodes = listOf(input("filled", value = "kept"), input("name_field", value = "")),
                primary = action("go", enabled = false),
            )

        val step = OnboardingWalker.nextStep(state, displayName = "Seed Name")

        assertEquals(PresentationEvent.textValue("s", "name_field", "Seed Name"), step?.event)
    }

    @Test
    fun activates_the_first_enabled_row_when_there_is_neither_primary_nor_empty_input() {
        val state =
            state(
                nodes =
                    listOf(
                        PresentationNode.ListNode(
                            id = "choices",
                            label = null,
                            rows =
                                listOf(
                                    row(activation = action("disabled_choice", enabled = false)),
                                    row(activation = action("first_choice", enabled = true)),
                                ),
                            searchable = false,
                            accessibility = a11y,
                        ),
                    ),
                primary = null,
            )

        val step = OnboardingWalker.nextStep(state)

        assertEquals(PresentationEvent.ActionActivated("s", "first_choice"), step?.event)
    }

    @Test
    fun yields_nothing_on_a_surface_with_no_affordance() {
        val state = state(nodes = listOf(input("done", value = "x")), primary = action("go", enabled = false))

        assertNull(OnboardingWalker.nextStep(state))
    }

    @Test
    fun yields_nothing_before_core_has_rendered_a_surface() {
        assertNull(OnboardingWalker.nextStep(PresentationState()))
    }

    private val a11y = AccessibilitySpec(label = "", description = null)

    private fun action(
        id: String,
        enabled: Boolean,
    ): ActionSpec =
        ActionSpec(
            interactionId = id,
            label = id,
            accessibilityLabel = id,
            iconToken = null,
            enabled = enabled,
            tone = ActionTone.Standard,
            shortcut = null,
        )

    private fun input(
        bindingId: String,
        value: String,
    ): PresentationNode.Input =
        PresentationNode.Input(
            bindingId = bindingId,
            label = bindingId,
            value = value,
            placeholder = null,
            inputKind = "text",
            maxLength = null,
            validationError = null,
            enabled = true,
            accessibility = a11y,
        )

    private fun row(activation: ActionSpec): PresentationRow =
        PresentationRow(
            title = activation.interactionId,
            subtitle = null,
            detail = null,
            iconToken = null,
            imageData = null,
            fallbackText = null,
            selected = false,
            enabled = true,
            activation = activation,
            secondaryActions = emptyList(),
            controls = emptyList(),
            accessibility = a11y,
        )

    private fun state(
        nodes: List<PresentationNode>,
        primary: ActionSpec?,
    ): PresentationState {
        val surface =
            SurfaceSpec(
                surfaceId = "s",
                revision = 7uL,
                title = "",
                subtitle = null,
                accessibilityLabel = "",
                layout = "stack",
                tokens = PresentationTokens(4, 8, 16, 8, 48),
                nodes = nodes,
            )
        return PresentationState(
            surfaces = mapOf("s" to surface),
            bars = mapOf("s" to RevisionedBar(7uL, ContextBar(back = null, navigation = null, primary = primary, secondary = null))),
        )
    }
}
