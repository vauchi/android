// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.debug

import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationNode
import app.vauchi.ui.presentation.PresentationState

/**
 * Drives Core's onboarding to completion for `--reset-for-testing` by
 * replaying the events a user would produce, one per rendered surface.
 *
 * The device-automation scripts launch a wiped debug build and expect it
 * to reach the main screen without a human. Creating the identity from
 * the shell would decide what "a fresh identity" contains; walking the
 * onboarding leaves that with Core's own completion path, so the seeded
 * device matches what a real first run produces (ADR-066).
 *
 * Every id is read from the surface Core rendered, never constructed
 * here. The walk is structural: press the enabled primary action, else
 * fill the first empty input, else activate the first row.
 */
object OnboardingWalker {
    const val SEED_DISPLAY_NAME: String = "Test User"

    /**
     * One event and the surface revision it was computed against. A
     * caller that re-dispatches only when the step differs from the last
     * one cannot spin on a surface Core refuses to advance.
     */
    data class Step(
        val event: PresentationEvent,
        val revision: ULong,
    )

    fun nextStep(
        state: PresentationState,
        displayName: String = SEED_DISPLAY_NAME,
    ): Step? {
        val surfaceId = state.activeSurfaceId ?: return null
        val surface = state.surfaces[surfaceId] ?: return null
        val event = nextEvent(state, surfaceId, surface.nodes, displayName) ?: return null
        return Step(event, surface.revision)
    }

    private fun nextEvent(
        state: PresentationState,
        surfaceId: String,
        nodes: List<PresentationNode>,
        displayName: String,
    ): PresentationEvent? {
        state.bars[surfaceId]
            ?.bar
            ?.primary
            ?.takeIf { it.enabled }
            ?.let { return PresentationEvent.ActionActivated(surfaceId, it.interactionId) }

        nodes
            .filterIsInstance<PresentationNode.Input>()
            .firstOrNull { it.enabled && it.value.isEmpty() }
            ?.let { return PresentationEvent.textValue(surfaceId, it.bindingId, displayName) }

        return nodes
            .filterIsInstance<PresentationNode.ListNode>()
            .flatMap { it.rows }
            .firstNotNullOfOrNull { row -> row.activation?.takeIf { row.enabled && it.enabled } }
            ?.let { PresentationEvent.ActionActivated(surfaceId, it.interactionId) }
    }
}
