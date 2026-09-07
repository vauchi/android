// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The group-visibility control is a `Toggle`, and it is the one presentation
 * primitive that renders two independently actionable things: a `clickable`
 * `Row` and a `Switch` with its own `onCheckedChange`. If the `Switch` becomes
 * its own accessibility node it carries no label, leaving a screen-reader user
 * unable to tell which group a toggle governs — on the control that decides who
 * can see a card field.
 *
 * Renders the primitive directly rather than navigating the app, so the
 * assertion is about the primitive and not about how some screen reaches it.
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class PresentationTogglePrimitiveTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun toggleExposesExactlyOneLabelledActionableNode() {
        composeTestRule.setContent {
            PresentationNodeRenderer(
                surfaceId = "surface-test",
                node =
                    PresentationNode.Toggle(
                        bindingId = "visibility.family",
                        label = "Family",
                        value = false,
                        enabled = true,
                        accessibility = AccessibilitySpec("Family", null),
                    ),
                onEvent = {},
                onCameraPermissionDenied = {},
                focusedBindingId = null,
                onFocusedBinding = { _, _ -> },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot(useUnmergedTree = false).printToLog("A11Y_TOGGLE")

        val actionable =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()

        val unlabeled =
            actionable.filter { node ->
                val hasText =
                    SemanticsProperties.Text in node.config &&
                        node.config[SemanticsProperties.Text].any { it.text.isNotEmpty() }
                val hasDesc =
                    SemanticsProperties.ContentDescription in node.config &&
                        node.config[SemanticsProperties.ContentDescription].any { it.isNotEmpty() }
                !hasText && !hasDesc
            }

        assertEquals(
            0,
            unlabeled.size,
            "Toggle exposed ${unlabeled.size} actionable node(s) with no label, " +
                "of ${actionable.size} actionable: " +
                unlabeled.map { it.boundsInRoot },
        )
    }

    /**
     * A settings row renders its toggle with `fillWidth = false`, so this is
     * the shape a screen reader actually meets. Asserting the state — not just
     * that a labelled node exists — is what distinguishes a switch that
     * announces "on" from one a blind user can flip but never read back.
     */
    @Test
    fun toggleAnnouncesItsOnStateBesideARowTitle() {
        composeTestRule.setContent {
            PresentationNodeRenderer(
                surfaceId = "surface-test",
                node =
                    PresentationNode.Toggle(
                        bindingId = "settings.delivery-receipts",
                        label = "",
                        value = true,
                        enabled = true,
                        accessibility = AccessibilitySpec("Delivery Receipts toggle", null),
                    ),
                onEvent = {},
                onCameraPermissionDenied = {},
                focusedBindingId = null,
                onFocusedBinding = { _, _ -> },
                fillWidth = false,
            )
        }
        composeTestRule.waitForIdle()

        val toggleable =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .filter { SemanticsProperties.ToggleableState in it.config }

        assertEquals(
            1,
            toggleable.size,
            "expected exactly one node carrying ToggleableState; " +
                "got ${toggleable.size}. Full tree: " +
                composeTestRule.onRoot().fetchSemanticsNode().config,
        )
        assertEquals(
            ToggleableState.On,
            toggleable.single().config[SemanticsProperties.ToggleableState],
        )
        assertEquals(
            listOf("Delivery Receipts toggle"),
            toggleable.single().config[SemanticsProperties.ContentDescription],
        )
    }
}
