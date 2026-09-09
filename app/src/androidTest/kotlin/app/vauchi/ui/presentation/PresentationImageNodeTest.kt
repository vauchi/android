// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * A standalone avatar is drawn as an avatar, not as a full-width band.
 *
 * `PresentationNode.Image` carries `circular`, which the shell honours by
 * clipping its `Surface` to `CircleShape` — but the same `Surface` claims
 * `fillMaxWidth()`, so the clip produced a stadium as wide as the screen
 * rather than a circle. With no picture the initials then sat in a
 * short, screen-wide pill instead of an avatar.
 *
 * `RowAvatar` in the same file has always been right: a fixed 40 dp box,
 * clipped and filled. This is the standalone node catching up.
 *
 * Asserting on the bounds rather than on a screenshot: a circle that is
 * not square is not a circle, and width-versus-height is the property
 * that actually broke.
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class PresentationImageNodeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val label = "Avatar for Bob"

    private fun render(
        fallbackText: String?,
        circular: Boolean,
    ) {
        composeTestRule.setContent {
            PresentationNodeRenderer(
                surfaceId = "surface-test",
                node =
                    PresentationNode.Image(
                        id = null,
                        data = null,
                        fallbackText = fallbackText,
                        circular = circular,
                        brightness = 1.0,
                        activation = null,
                        accessibility = AccessibilitySpec(label, null),
                    ),
                onEvent = {},
                onCameraPermissionDenied = {},
            )
        }
    }

    @Test
    fun circularAvatarIsSquare() {
        render(fallbackText = "BS", circular = true)

        val bounds =
            composeTestRule
                .onNodeWithContentDescription(label)
                .fetchSemanticsNode()
                .size
        val skew = abs(bounds.width - bounds.height)

        assertTrue(
            skew <= 2,
            "a circular avatar must be square; measured ${bounds.width} x ${bounds.height}",
        )
    }

    /**
     * Guards the assertions around it: an avatar with nothing to show must
     * not leave a filled blank behind. Without this, a renderer that drew a
     * fixed square for every input — including no input — would satisfy
     * every other test in this class.
     */
    @Test
    fun anAvatarWithNothingToShowDrawsNothing() {
        render(fallbackText = null, circular = true)

        composeTestRule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    /**
     * The fixed avatar side is a shell choice — Core names no size — but it
     * has to be large enough to read and to hit. Pinned so a later tidy
     * cannot quietly shrink it below the touch-target floor.
     */
    @Test
    fun theAvatarIsAtLeastATouchTargetAcross() {
        render(fallbackText = "BS", circular = true)

        val widthPx =
            composeTestRule
                .onNodeWithContentDescription(label)
                .fetchSemanticsNode()
                .size.width
        val widthDp = with(composeTestRule.density) { widthPx.toDp() }

        assertTrue(
            widthDp >= 48.dp,
            "the avatar is $widthDp across, under the 48 dp touch-target floor",
        )
    }
}
