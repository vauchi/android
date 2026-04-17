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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vauchi.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural UI tests — verify the rendered view hierarchy exposes specific
 * labelled elements (content descriptions / text) that screen readers rely on.
 * Uses `reset_for_testing` to bypass onboarding and land on the home screen.
 *
 * Traces to: features/accessibility.feature
 *
 * Note on CC-20 (Test Quality Rules): earlier versions asserted
 * `isNotEmpty()` on `onAllNodes(hasClickAction())` — near-tautology that passes
 * for any non-empty screen. The assertions below query specific
 * `contentDescription` values that exist in `MainActivity.kt`, so a real
 * regression (missing tab, missing a11y label) will fail the test.
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
        // Bottom nav is gated by `uiState is UiState.Ready` in MainActivity.kt.
        // `waitForIdle()` only settles Compose recomposition, not async state
        // load — wait for the first tab's contentDescription to appear so the
        // tree is stable before assertions. Fails fast if the app never
        // reaches Ready within READY_TIMEOUT_MS.
        composeTestRule.waitUntil(READY_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithContentDescription(EXPECTED_BOTTOM_NAV_LABELS.first())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @After
    fun cleanup() {
        scenario.close()
    }

    // -- Navigation Structure --

    /**
     * Every bottom-nav entry declared in MainActivity.kt must be rendered with
     * the expected content description. Fails if a tab goes missing or loses
     * its accessibility label.
     */
    @Test
    fun bottomNavigation_rendersAllExpectedTabs() {
        composeTestRule.waitForIdle()
        for (tabLabel in EXPECTED_BOTTOM_NAV_LABELS) {
            composeTestRule
                .onAllNodesWithContentDescription(tabLabel)
                .fetchSemanticsNodes()
                .let { nodes ->
                    assertTrue(
                        nodes.isNotEmpty(),
                        "Bottom-nav tab '$tabLabel' missing — no node with that contentDescription",
                    )
                }
        }
    }

    // -- Accessible Labels --

    /**
     * Every clickable in the live hierarchy must expose either text or a
     * contentDescription — required by TalkBack.
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

        val unlabeled = mutableListOf<Int>()
        for ((i, node) in nodes.withIndex()) {
            val hasText =
                SemanticsProperties.Text in node.config &&
                    node.config[SemanticsProperties.Text].any { it.text.isNotEmpty() }
            val hasDesc =
                SemanticsProperties.ContentDescription in node.config &&
                    node.config[SemanticsProperties.ContentDescription].any { it.isNotEmpty() }
            if (!hasText && !hasDesc) unlabeled.add(i)
        }
        assertEquals(
            0,
            unlabeled.size,
            "Clickable nodes without text or contentDescription: $unlabeled (of ${nodes.size} total)",
        )
    }

    // -- Content Rendering --

    /**
     * Home screen should render at least one node with text semantics — keeps
     * a minimal smoke signal, but the real coverage is in the tab-presence
     * and labelled-clickables tests above.
     */
    @Test
    fun homeScreen_rendersTextContent() {
        composeTestRule.waitForIdle()
        val textNodes =
            composeTestRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes()
        // The tab strip alone contributes ≥5 text/label nodes, plus any
        // screen content. A floor of 5 catches "home screen failed to
        // render" without being overly specific.
        assertTrue(
            textNodes.size >= 5,
            "Home screen should render ≥5 text nodes (tab bar + content); found ${textNodes.size}",
        )
    }

    private companion object {
        /**
         * Mirrors the contentDescription literals on each `NavigationBarItem`
         * in `MainActivity.kt`. Update both sides together when the bottom-nav
         * structure changes. These are NOT localized yet (tracked in the P0
         * frontend-pure-renderer-violations record, §4 i18n Gap); when Android
         * i18n is wired, replace with resource lookups.
         */
        val EXPECTED_BOTTOM_NAV_LABELS =
            listOf("My Card", "Contacts", "Exchange", "Groups", "More")

        const val READY_TIMEOUT_MS = 5_000L
    }
}
