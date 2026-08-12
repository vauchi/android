// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
 * Two harness realities this test pins down for the next author:
 *  - `reset_for_testing` seeds a throwaway identity but, on a fresh
 *    install, does NOT advance Onboarding → Ready on the same launch (the
 *    async seed lands after the screen resolves, leaving the app on the
 *    Welcome screen). The `@Before` recreates the activity until the seeded
 *    identity resolves to the card home. Harness bug filed 2026-07-26.
 *  - Adding an entry is reached through the context bar's secondary role,
 *    not from the surface: ADR-066 moved it into that overlay, so the flow
 *    is Actions → Add Entry rather than a button on the card.
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
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            ).apply {
                putExtra("reset_for_testing", true)
            }
        scenario = ActivityScenario.launch(intent)
        // Gate on the seeded identity rendering, which is the fixture this
        // test creates rather than any Core-supplied copy. Recreating
        // re-runs onCreate, which then observes the seeded identity and
        // resolves to the card home.
        var homeReady = false
        for (attempt in 0 until MAX_HOME_ATTEMPTS) {
            try {
                composeTestRule.waitUntil(HOME_POLL_MS) {
                    composeTestRule
                        .onAllNodesWithText(SEEDED_IDENTITY_NAME)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                homeReady = true
                break
            } catch (timeout: ComposeTimeoutException) {
                scenario.recreate()
                composeTestRule.waitForIdle()
            }
        }
        check(homeReady) {
            "App never reached the card home — reset_for_testing seed did " +
                "not resolve to Ready within $MAX_HOME_ATTEMPTS attempts"
        }
    }

    @After
    fun cleanup() {
        scenario.close()
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
        composeTestRule.waitForIdle()
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
        const val HOME_POLL_MS = 5_000L
        const val MAX_HOME_ATTEMPTS = 6

        /** Mirrors the identity `MainViewModel.seedTestIdentityIfNeeded()` creates. */
        const val SEEDED_IDENTITY_NAME = "Test User"

        const val ACTIONS_ROLE = "Actions"
        const val ADD_ENTRY = "Add Entry"
        const val SAVE = "Save"
        const val VALUE_FIELD = "Value input"
        const val PHONE_TYPE = "Phone"
        const val PHONE_VALUE = "+12025550199"
    }
}
