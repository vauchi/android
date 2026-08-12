// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vauchi.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Structural UI tests — verify the rendered hierarchy exposes the labels screen
 * readers rely on. Uses `reset_for_testing` to seed an identity and land on the
 * home surface.
 *
 * Traces to: features/accessibility.feature
 *
 * These assertions deliberately name no Core-supplied copy. Under ADR-066 the
 * shell renders what Core sends and must not know what a surface is "about", so
 * pinning visible wording pins something the shell does not own.
 *
 * That is not hypothetical. The previous revision asserted a five-entry
 * bottom-nav strip by name and gated `@Before` on the first tab's
 * contentDescription. ADR-066 replaced the strip with one Core-driven surface
 * plus the context bar, so the gate waited 5s for a tab that no longer existed
 * and every test here died in setup — including the labelled-affordance check
 * below. A precondition naming removed UI turns any real finding into a
 * timeout, and a timeout reads as a broken test rather than a broken app.
 *
 * A companion count assertion ("home renders ≥5 text nodes") was removed for
 * the same reason: its floor was calibrated on the deleted tab strip, and the
 * `@Before` gate below already proves the surface rendered.
 */
@RunWith(AndroidJUnit4::class)
class StructuralUITest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchWithTestIdentity() {
        scenario = launchSeededApp(composeTestRule)
    }

    @After
    fun cleanup() {
        // `launchSeededApp` closes the scenario and throws before assigning
        // when the app never reaches Ready. Closing an unset lateinit here
        // would replace its diagnosis with an UninitializedPropertyAccess.
        if (::scenario.isInitialized) scenario.close()
    }

    // -- Accessible Labels --

    /**
     * Every actionable node in the live hierarchy must expose either text or a
     * contentDescription — required by TalkBack.
     *
     * `hasClickAction()` also matches toggleables, since Compose gives a
     * `Switch` an `OnClick` action.
     *
     * Note on CC-20: an earlier revision asserted `isNotEmpty()` on the same
     * query, which passes for any non-empty screen. This inspects each node's
     * semantics instead, so an affordance losing its label fails it.
     */
    @Test
    fun allClickableElements_haveAccessibleLabels() {
        composeTestRule.waitForIdle()
        val nodes =
            composeTestRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
        // Precondition: the home screen is non-trivial. If this ever fails it
        // means the launch landed on an empty screen — investigate the intent
        // extra handling, not the assertion.
        require(nodes.isNotEmpty()) { "Home screen rendered no clickable nodes" }

        val unlabeled = mutableListOf<String>()
        for (node in nodes) {
            val hasText =
                SemanticsProperties.Text in node.config &&
                    node.config[SemanticsProperties.Text].any { it.text.isNotEmpty() }
            val hasDesc =
                SemanticsProperties.ContentDescription in node.config &&
                    node.config[SemanticsProperties.ContentDescription].any { it.isNotEmpty() }
            // Report position and role, not an index — an actionable node with
            // no label has nothing else to identify it by.
            if (!hasText && !hasDesc) {
                val role =
                    if (SemanticsProperties.Role in node.config) {
                        node.config[SemanticsProperties.Role].toString()
                    } else {
                        "no-role"
                    }
                unlabeled += "$role@${node.boundsInRoot}"
            }
        }
        assertEquals(
            0,
            unlabeled.size,
            "Actionable nodes without text or contentDescription: $unlabeled (of ${nodes.size} total)",
        )
    }
}
