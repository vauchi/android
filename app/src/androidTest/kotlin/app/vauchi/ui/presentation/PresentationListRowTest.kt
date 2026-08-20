// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * A row renders everything Core attaches to it, in the right place.
 *
 * `PresentationRow` carries an avatar (`imageData` / `fallbackText`) and
 * `controls` alongside its text. Android rendered neither the avatar nor
 * the controls beside the title: contact rows showed no initial circle
 * where iOS showed one, and a settings switch sat stranded under an empty
 * line because the row title, not the control, names the setting
 * (`_private/docs/problems/2026-08-20-ios-settings-toggles-render-no-control/`).
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class PresentationListRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        title: String,
        fallbackText: String? = null,
        controls: List<PresentationNode> = emptyList(),
    ) = PresentationRow(
        title = title,
        subtitle = null,
        detail = null,
        iconToken = null,
        imageData = null,
        fallbackText = fallbackText,
        selected = false,
        enabled = true,
        activation = null,
        secondaryActions = emptyList(),
        controls = controls,
        accessibility = AccessibilitySpec(title, null),
    )

    private fun render(row: PresentationRow) {
        composeTestRule.setContent {
            PresentationNodeRenderer(
                surfaceId = "surface-test",
                node =
                    PresentationNode.ListNode(
                        id = "list.test",
                        label = null,
                        rows = listOf(row),
                        searchable = false,
                        accessibility = AccessibilitySpec("List", null),
                    ),
                onEvent = {},
                onCameraPermissionDenied = {},
                focusedBindingId = null,
                onFocusedBinding = { _, _ -> },
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun rowRendersTheAvatarFallbackCoreSupplies() {
        render(row(title = "Ada", fallbackText = "A"))

        composeTestRule.onNodeWithText("A").assertIsDisplayed()
    }

    @Test
    fun rowControlSitsBesideTheTitleRatherThanBeneathIt() {
        render(
            row(
                title = "Delivery Receipts",
                controls =
                    listOf(
                        PresentationNode.Toggle(
                            bindingId = "settings.delivery_receipts",
                            label = "",
                            value = true,
                            enabled = true,
                            accessibility = AccessibilitySpec("Delivery Receipts", null),
                        ),
                    ),
            ),
        )

        val title =
            composeTestRule
                .onNodeWithText("Delivery Receipts")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val control =
            composeTestRule
                .onNode(isToggleable())
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(
            control.left >= title.right,
            "Row control should start right of the title, but the title ends " +
                "at ${title.right} and the control starts at ${control.left} " +
                "(title=$title control=$control)",
        )
    }
}
