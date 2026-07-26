// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
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
 * **semantics** (testTags + `performTextInput`) instead of blind
 * `uiautomator` coordinate taps.
 *
 * Motivation: the on-device device-test session (2026-07-26) drove this
 * flow with coordinate taps + `adb input text`, which garbled the value
 * (`+12025550199` → `Riaggc9669`), failed the Phone field's validation,
 * and could not reliably actuate the Compose form. `performTextInput`
 * enters the exact value; the E.164 number passes validation, so the field
 * persists and renders under the owner card.
 *
 * Two harness realities this test pins down for the next author:
 *  - `reset_for_testing` seeds a throwaway identity but, on a fresh
 *    install, does NOT advance Onboarding → Ready on the same launch (the
 *    async seed lands after the screen resolves, leaving the app on the
 *    Welcome screen). The `@Before` recreates the activity until the seeded
 *    identity resolves to the My Card home. Harness bug filed 2026-07-26.
 *  - Flipping a field to shared (its per-field visibility control) opens
 *    from the card field row; asserting that through semantics needs the
 *    field-detail's composition root and is tracked as follow-up. The
 *    two-device device-scoped-mailbox DELIVERY it gates is certified by the
 *    e2e `rg4-rg5` lane + core unit tests (`scanner_fans_out_*`,
 *    `ack_routes_*`) regardless of this UI path.
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
        // reset_for_testing seeds an identity, but on a fresh install the
        // Onboarding → Ready transition does not fire on the same launch.
        // Recreating re-runs onCreate, which then observes the seeded
        // identity and resolves to the My Card home; poll-then-recreate
        // until its add-field affordance appears.
        var homeReady = false
        for (attempt in 0 until MAX_HOME_ATTEMPTS) {
            try {
                composeTestRule.waitUntil(HOME_POLL_MS) {
                    composeTestRule
                        .onAllNodesWithTag(ADD_FIELD_TAG)
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
            "App never reached the My Card home — reset_for_testing seed did " +
                "not resolve to Ready within $MAX_HOME_ATTEMPTS attempts"
        }
    }

    @After
    fun cleanup() {
        scenario.close()
    }

    @Test
    fun addingAPhoneFieldWithAValidNumber_rendersItOnTheOwnerCard() {
        composeTestRule.onNodeWithTag(ADD_FIELD_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(PHONE_TYPE).onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(FIELD_VALUE_TAG).performTextInput(PHONE_VALUE)
        composeTestRule.onNodeWithTag(SUBMIT_TAG).performClick()
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

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val HOME_POLL_MS = 5_000L
        const val MAX_HOME_ATTEMPTS = 6
        const val ADD_FIELD_TAG = "add_field"
        const val FIELD_VALUE_TAG = "field_value"
        const val SUBMIT_TAG = "submit"
        const val PHONE_TYPE = "Phone"
        const val PHONE_VALUE = "+12025550199"
    }
}
