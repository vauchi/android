// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Core decides how many destinations the navigation overlay carries, so the
 * shell cannot assume they fit: on a Pixel 3a the ten-destination menu clipped
 * its last entry to a 13dp sliver that a screen-reader user could not focus and
 * a touch user could barely hit.
 *
 * Renders the overlay directly in a viewport too short for its contents rather
 * than navigating the app, so the assertion is about the panel's own layout and
 * not about which screen happens to open it.
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class PresentationOverlayReachabilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun destination(index: Int) =
        ActionSpec(
            interactionId = "nav.$index",
            label = "Destination $index",
            accessibilityLabel = "Destination $index",
            iconToken = null,
            enabled = true,
            tone = ActionTone.Standard,
            shortcut = null,
        )

    @Test
    fun everyNavigationDestinationReachesFullHeightInAShortViewport() {
        val destinations = (1..10).map(::destination)

        composeTestRule.setContent {
            Box(modifier = Modifier.requiredSize(width = 392.dp, height = 460.dp)) {
                PresentationOverlay(
                    overlay =
                        RevisionedOverlay(
                            surfaceId = "surface-test",
                            revision = 1uL,
                            overlay =
                                OverlaySpec(
                                    kind = OverlayKind.Navigation,
                                    title = "More",
                                    items = destinations,
                                ),
                        ),
                    windowClass = WindowClass.Compact,
                    reducedMotion = true,
                    onAction = {},
                    onDismiss = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        destinations.forEach { destination ->
            composeTestRule
                .onNodeWithText(destination.label)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(40.dp)
        }
    }
}
