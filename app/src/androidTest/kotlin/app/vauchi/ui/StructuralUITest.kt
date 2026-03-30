// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vauchi.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Structural UI tests — zero coupling to core action IDs or localized strings.
 * Uses reset_for_testing intent extra to bypass onboarding.
 * Verifies: content rendering, navigation structure, element accessibility.
 *
 * Traces to: features/accessibility.feature
 */
@RunWith(AndroidJUnit4::class)
class StructuralUITest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchWithTestIdentity() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            ).apply {
                putExtra("reset_for_testing", true)
            }
        scenario = ActivityScenario.launch(intent)
        composeTestRule.waitForIdle()
    }

    @After
    fun cleanup() {
        scenario.close()
    }

    // -- Content Rendering --

    @Test
    fun homeScreen_rendersTextContent() {
        composeTestRule.waitForIdle()
        val textNodes =
            composeTestRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes()
        assertTrue(textNodes.isNotEmpty(), "Home screen should display text content")
    }

    @Test
    fun homeScreen_hasClickableElements() {
        composeTestRule.waitForIdle()
        val clickables =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty(), "Home screen should have clickable elements")
    }

    // -- Accessible Labels --

    @Test
    fun allClickableElements_haveAccessibleLabels() {
        composeTestRule.waitForIdle()
        val nodes =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "Should have at least one clickable element")

        for ((i, node) in nodes.withIndex()) {
            val hasText =
                SemanticsProperties.Text in node.config &&
                    node.config[SemanticsProperties.Text].any { it.text.isNotEmpty() }
            val hasDesc =
                SemanticsProperties.ContentDescription in node.config &&
                    node.config[SemanticsProperties.ContentDescription].any { it.isNotEmpty() }
            assertTrue(
                hasText || hasDesc,
                "Clickable element $i has no accessible text or content description",
            )
        }
    }

    // -- Navigation Structure --

    @Test
    fun bottomNavigation_hasLabeledItems() {
        composeTestRule.waitForIdle()
        // Bottom navigation items are clickable elements with text labels.
        // Structural: at least 3 exist (exact count may change with core).
        val nodes =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
        val labeledClickables =
            nodes.count { node ->
                SemanticsProperties.Text in node.config &&
                    node.config[SemanticsProperties.Text].any { it.text.isNotEmpty() }
            }
        assertTrue(
            labeledClickables >= 3,
            "Should have at least 3 labeled clickable elements, found $labeledClickables",
        )
    }
}
