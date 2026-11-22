// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PinnedActionsRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping the second pinned answer emits only its own action activation`() {
        val events = mutableListOf<PresentationEvent>()
        val actions =
            listOf("Yes, reconnect Alex", "No, stop the exchange").mapIndexed { index, label ->
                ActionSpec(
                    interactionId = "surface.7.context.presentation.pinned.$index",
                    label = label,
                    accessibilityLabel = label,
                    iconToken = null,
                    enabled = true,
                    tone = ActionTone.Standard,
                    shortcut = null,
                )
            }
        val surface =
            SurfaceSpec(
                surfaceId = "exchange",
                revision = 7uL,
                title = "Reconnect?",
                subtitle = null,
                accessibilityLabel = "Reconnect?",
                layout = SURFACE_LAYOUT_FIXED,
                tokens = DefaultPresentationTokens,
                nodes = emptyList(),
            )
        val bar = ContextBar(back = null, navigation = null, primary = null, secondary = null, pinned = actions)

        composeRule.setContent {
            MaterialTheme {
                PresentationSurface(
                    surface = surface,
                    active = true,
                    bar = bar,
                    navigationShown = false,
                    onActivate = {},
                    onEvent = events::add,
                    onCameraPermissionDenied = {},
                    focusedBindingId = null,
                    onFocusedBinding = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Yes, reconnect Alex").assertIsDisplayed()
        composeRule.onNodeWithText("No, stop the exchange").assertIsDisplayed().performClick()

        assertEquals(
            listOf<PresentationEvent>(
                PresentationEvent.ActionActivated(
                    surfaceId = "exchange",
                    interactionId = "surface.7.context.presentation.pinned.1",
                ),
            ),
            events,
        )
    }
}
