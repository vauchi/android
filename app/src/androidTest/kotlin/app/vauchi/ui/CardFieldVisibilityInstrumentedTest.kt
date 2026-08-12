// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vauchi.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the card field-add flow, driven through Compose
 * **semantics** instead of blind `uiautomator` coordinate taps.
 *
 * Motivation: the on-device session (2026-07-26) drove this flow with
 * coordinate taps + `adb input text`, which garbled the value
 * (`+12025550199` → `Riaggc9669`), failed the Phone field's validation,
 * and could not reliably actuate the Compose form. `performTextInput`
 * enters the exact value; the E.164 number passes validation, so the field
 * persists and renders under the owner card.
 *
 * Addressed by Core's accessibility labels, not test tags. This test used
 * `add_field`, `field_value` and `submit`, none of which exist in the app —
 * the only tags it defines are `error.retry` and the two `recovery.*`. They
 * predate ADR-066, when the shell owned its screens, and their loss left
 * the test failing in `@Before` for reasons that read as a UI regression.
 * Nor can they simply be reinstated: Core mints interaction and binding ids
 * per surface revision, and the shell cannot name an affordance itself
 * without the domain knowledge ADR-066 denies it. See
 * `e2e/maestro/README.md`, which settles the same question for the Maestro
 * flows.
 *
 * A harness reality this test pins down for the next author: adding an entry
 * is reached through the context bar's secondary role, not from the surface.
 * ADR-066 moved it into that overlay, so the flow is Actions → Add Entry
 * rather than a button on the card.
 *
 * The setup gate lives in [launchSeededApp], which waits on the state the
 * seed commits rather than on rendered text — see its KDoc for why.
 *
 * Traces to: features/contact_card.feature
 */
@RunWith(AndroidJUnit4::class)
class CardFieldVisibilityInstrumentedTest {
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

    @Test
    fun addingAPhoneFieldWithAValidNumber_rendersItOnTheOwnerCard() {
        // Add Entry lives in the context bar's secondary overlay (ADR-066),
        // so open that first. The role button is icon-only, hence addressed
        // by its accessibility label rather than by text.
        // Addressed by contentDescription throughout: the renderer sets it
        // from Core's accessibility label, and the visible text lands on a
        // separate node, so a text matcher finds nothing.
        openActionsOverlay()
        composeTestRule.onAllNodesWithContentDescription(ADD_ENTRY).onFirst().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithContentDescription(PHONE_TYPE).onFirst().performClick()
        // Wait for the field rather than assuming `waitForIdle()` covers it:
        // picking a type swaps in the entry form, and probing the frame before
        // it lands fails with "could not find any node", which reads as a
        // missing label rather than as arriving early.
        composeTestRule.waitUntil(OVERLAY_WAIT_MS) {
            composeTestRule
                .onAllNodesWithContentDescription(VALUE_FIELD, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // useUnmergedTree: the field's own label lives below the merge
        // boundary, so the merged tree has no node carrying it.
        composeTestRule
            .onNodeWithContentDescription(VALUE_FIELD, useUnmergedTree = true)
            .performTextInput(PHONE_VALUE)
        composeTestRule.onAllNodesWithContentDescription(SAVE).onFirst().performClick()
        composeTestRule.waitForIdle()

        // The saved field renders on the owner card — proves the exact E.164
        // value was entered (no garbling) and accepted by Phone validation.
        // A garbled or malformed value is rejected before it reaches here.
        composeTestRule.waitUntil(READY_TIMEOUT_MS) {
            composeTestRule
                .onAllNodes(hasText(PHONE_VALUE, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule
            .onAllNodes(hasText(PHONE_VALUE, substring = true))
            .onFirst()
            .assertExists("The added phone field did not render on the owner card")
    }

    /**
     * The secondary role button *toggles* its overlay, so a single tap only
     * opens it from a known-closed state. Assuming that made this flaky —
     * one run reached the value field, the next could not find Add Entry.
     * Tap until the overlay's contents are actually present.
     */
    private fun overlayIsOpen() =
        composeTestRule
            .onAllNodesWithContentDescription(ADD_ENTRY)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun openActionsOverlay() {
        repeat(OVERLAY_OPEN_ATTEMPTS) {
            if (overlayIsOpen()) return
            composeTestRule.onNodeWithContentDescription(ACTIONS_ROLE).performClick()
            try {
                // Wait for the overlay rather than probing straight after
                // `waitForIdle()`. Probing immediately reads the frame before
                // it animates in, and because the button toggles, the retry
                // then closes what the first tap opened — the loop oscillates
                // and ends closed.
                composeTestRule.waitUntil(OVERLAY_WAIT_MS) { overlayIsOpen() }
                return
            } catch (timeout: ComposeTimeoutException) {
                composeTestRule.waitForIdle()
            }
        }
        check(overlayIsOpen()) { "Actions overlay never exposed $ADD_ENTRY" }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val OVERLAY_OPEN_ATTEMPTS = 3
        const val OVERLAY_WAIT_MS = 3_000L

        const val ACTIONS_ROLE = "Actions"
        const val ADD_ENTRY = "Add Entry"
        const val SAVE = "Save"
        const val VALUE_FIELD = "Value input"
        const val PHONE_TYPE = "Phone"
        const val PHONE_VALUE = "+12025550199"
    }
}
