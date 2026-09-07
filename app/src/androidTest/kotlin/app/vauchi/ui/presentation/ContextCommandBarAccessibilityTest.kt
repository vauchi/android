// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The command bar carries the only way forward on several screens — on
 * onboarding it is the sole control. A button that renders but publishes no
 * click action is operable by sighted touch and unreachable by every assistive
 * technology, which is the failure mode hardest to notice by looking.
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class ContextCommandBarAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun action(
        id: String,
        label: String,
    ) = ActionSpec(
        interactionId = id,
        label = label,
        accessibilityLabel = label,
        iconToken = null,
        enabled = true,
        tone = ActionTone.Standard,
        shortcut = null,
    )

    @Test
    fun everyCommandBarButtonPublishesAClickAction() {
        composeTestRule.setContent {
            ContextCommandBar(
                surfaceId = "surface-test",
                bar =
                    ContextBar(
                        back = action("back", "Back"),
                        navigation = action("nav", "More"),
                        primary = action("primary", "Create new identity"),
                        secondary = action("secondary", "Actions"),
                    ),
                windowClass = WindowClass.Compact,
                onEvent = {},
            )
        }
        composeTestRule.waitForIdle()

        val labelled =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .flatMap { node ->
                    if (SemanticsProperties.ContentDescription in node.config) {
                        node.config[SemanticsProperties.ContentDescription]
                    } else {
                        emptyList()
                    }
                }.sorted()

        assertEquals(
            listOf("Actions", "Back", "Create new identity", "More"),
            labelled,
        )
    }
}
