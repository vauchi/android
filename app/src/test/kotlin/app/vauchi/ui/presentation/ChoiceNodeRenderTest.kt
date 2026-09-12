// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `Choice` with two or three options is a mode switch (Perspective on
 * contact detail, Members / Visibility on a group), and the design canvas
 * draws it as a segmented control every option of which is one tap away.
 * Longer lists (Theme has fifteen entries) stay behind a dropdown, where
 * a row of fifteen segments would not fit.
 *
 * Both shapes send the same `choice` value event, so core cannot tell
 * which one the shell picked.
 */
@RunWith(RobolectricTestRunner::class)
class ChoiceNodeRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun choice(vararg labels: String) =
        PresentationNode.Choice(
            bindingId = "perspective",
            label = "Perspective",
            selected = "option-0",
            options = labels.mapIndexed { index, label -> ChoiceOption("option-$index", label) },
            enabled = true,
            accessibility = AccessibilitySpec("Perspective picker", null),
        )

    private fun render(
        node: PresentationNode.Choice,
        events: MutableList<PresentationEvent>,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PresentationNodeRenderer(
                    surfaceId = "contact_detail",
                    node = node,
                    onEvent = events::add,
                    onCameraPermissionDenied = {},
                )
            }
        }
    }

    @Test
    fun `two options render as segments, each one tap from a choice event`() {
        val events = mutableListOf<PresentationEvent>()
        render(choice("Their Info", "My Info for Them"), events)

        composeRule.onNodeWithText("Their Info").assertIsDisplayed()
        composeRule.onNodeWithText("My Info for Them").assertIsDisplayed().performClick()

        assertEquals(
            listOf(PresentationEvent.choiceValue("contact_detail", "perspective", "option-1")),
            events,
        )
    }

    @Test
    fun `segments keep the caption and the accessibility label from the node`() {
        render(choice("Members", "Visibility"), mutableListOf())

        composeRule.onNodeWithText("Perspective").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Perspective picker").assertIsDisplayed()
    }

    @Test
    fun `a long option list stays behind a dropdown until opened`() {
        val labels = (1..15).map { "Theme $it" }.toTypedArray()
        render(choice(*labels), mutableListOf())

        composeRule.onNodeWithText("Theme 1").assertIsDisplayed()
        composeRule.onNodeWithText("Theme 7").assertDoesNotExist()
    }
}
