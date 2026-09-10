// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vauchi.ui.theme.LocalStatusColors
import app.vauchi.ui.theme.StatusColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

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

    // The overlay's tone-coloured actions read StatusColors for the
    // warning colour; outside VauchiTheme (this test renders the overlay
    // standalone) nothing else provides it.
    private val statusColors =
        StatusColors(
            success = Color(0xFF2E7D32),
            warning = Color(0xFFF9A825),
            info = Color(0xFF1976D2),
        )

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
            CompositionLocalProvider(LocalStatusColors provides statusColors) {
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

    /**
     * The panel grows to the viewport only because a long menu needs it. A
     * short one that keeps the full height covers the scrim, so the gesture
     * everyone reaches for first — tap beside the menu to close it — does
     * nothing: on a Galaxy S7 in onboarding, one destination sat in a panel
     * spanning the whole content area, roughly 85% of it empty.
     */
    @Test
    fun aShortNavigationMenuLeavesTheScrimReachable() {
        var dismissed = false

        composeTestRule.setContent {
            CompositionLocalProvider(LocalStatusColors provides statusColors) {
                Box(modifier = Modifier.requiredSize(width = 392.dp, height = 800.dp)) {
                    PresentationOverlay(
                        overlay =
                            RevisionedOverlay(
                                surfaceId = "surface-test",
                                revision = 1uL,
                                overlay =
                                    OverlaySpec(
                                        kind = OverlayKind.Navigation,
                                        title = "More",
                                        items = listOf(destination(1)),
                                    ),
                            ),
                        windowClass = WindowClass.Compact,
                        reducedMotion = true,
                        onAction = {},
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            click(Offset(centerX, height * 0.92f))
        }
        composeTestRule.waitForIdle()

        assertTrue(
            dismissed,
            "a tap well below a one-item menu must reach the scrim and dismiss it",
        )
    }
}
