// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Core's context bar carries the only way forward on several screens — on
 * onboarding it is the sole control. Since vauchi/private#534 the title
 * row draws back/navigation/info/actions and a full-width button under
 * the content draws primary; a control that renders but publishes no
 * click action is operable by sighted touch and unreachable by every
 * assistive technology, which is the failure mode hardest to notice by
 * looking.
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
    fun everyContextBarControlPublishesAClickAction() {
        val surfaceId = "surface-test"
        val bar =
            ContextBar(
                back = action("back", "Back"),
                navigation = action("nav", "More"),
                primary = action("primary", "Create new identity"),
                secondary = action("secondary", "Actions"),
                info = action("info", "About this screen"),
            )
        val surface =
            SurfaceSpec(
                surfaceId = surfaceId,
                revision = 1u,
                title = "Test surface",
                subtitle = null,
                accessibilityLabel = "Test surface content",
                layout = "list",
                tokens = DefaultPresentationTokens,
                nodes = emptyList(),
            )
        val profile =
            PresentationProfile(
                windowClass = WindowClass.Compact,
                paneLayout = PaneLayout.Single,
                primarySurface = surfaceId,
                detailSurface = null,
                activeSurface = surfaceId,
            )
        val state =
            PresentationState(
                surfaces = mapOf(surfaceId to surface),
                bars = mapOf(surfaceId to RevisionedBar(1u, bar)),
                profile = profile,
            )

        composeTestRule.setContent {
            PresentationScreen(
                state = state,
                profile = profile,
                activeSurfaceId = surfaceId,
                reducedMotion = false,
                focusedBindingId = null,
                onFocusedBinding = { _, _ -> },
                actions =
                    PresentationScreenActions(
                        onEvent = { _, _ -> },
                        onSurfaceActivated = {},
                        onCameraPermissionDenied = {},
                        onDismissOverlay = {},
                    ),
            )
        }
        composeTestRule.waitForIdle()

        // back and navigation both lead the title row (#534); neither
        // picks one over the other, same as the retired bottom row.
        listOf(
            "contextbar.back" to "Back",
            "contextbar.navigation" to "More",
            "contextbar.info" to "About this screen",
            "contextbar.secondary" to "Actions",
            "contextbar.primary" to "Create new identity",
        ).forEach { (tag, label) ->
            val node =
                composeTestRule
                    .onNode(hasTestTag(tag) and hasClickAction())
                    .fetchSemanticsNode()
            assertEquals(
                label,
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull(),
                "control tagged $tag should publish a click action labelled \"$label\"",
            )
        }
    }

    @Test
    fun absentSlotsDrawNoControl() {
        val surfaceId = "surface-test"
        val bar = ContextBar(back = null, navigation = null, primary = null, secondary = null, info = null)
        val surface =
            SurfaceSpec(
                surfaceId = surfaceId,
                revision = 1u,
                title = "Test surface",
                subtitle = null,
                accessibilityLabel = "Test surface content",
                layout = "list",
                tokens = DefaultPresentationTokens,
                nodes = emptyList(),
            )
        val profile =
            PresentationProfile(
                windowClass = WindowClass.Compact,
                paneLayout = PaneLayout.Single,
                primarySurface = surfaceId,
                detailSurface = null,
                activeSurface = surfaceId,
            )
        val state =
            PresentationState(
                surfaces = mapOf(surfaceId to surface),
                bars = mapOf(surfaceId to RevisionedBar(1u, bar)),
                profile = profile,
            )

        composeTestRule.setContent {
            PresentationScreen(
                state = state,
                profile = profile,
                activeSurfaceId = surfaceId,
                reducedMotion = false,
                focusedBindingId = null,
                onFocusedBinding = { _, _ -> },
                actions =
                    PresentationScreenActions(
                        onEvent = { _, _ -> },
                        onSurfaceActivated = {},
                        onCameraPermissionDenied = {},
                        onDismissOverlay = {},
                    ),
            )
        }
        composeTestRule.waitForIdle()

        listOf("contextbar.back", "contextbar.navigation", "contextbar.info", "contextbar.secondary", "contextbar.primary")
            .forEach { tag ->
                composeTestRule.onAllNodes(hasTestTag(tag)).assertCountEquals(0)
            }
    }
}
