// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import app.vauchi.ui.coreui.CelebrateRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: onboarding.feature — Exchange success celebration (private#276).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class CelebrationEffectTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var request by mutableStateOf<CelebrateRequest?>(null)

    @Before
    fun setUp() {
        composeRule.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                val celebrating = rememberCelebrating(request, reducedMotion = false) { request = null }
                CelebrationFlash(visible = celebrating, tokens = DefaultPresentationTokens)
            }
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun aCelebrationFlashDisappearsAfterItsBeat() {
        composeRule.onNodeWithTag(CELEBRATION_FLASH_TAG).assertDoesNotExist()

        composeRule.runOnIdle { request = CelebrateRequest("none", "none", "cards_meet") }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CELEBRATION_FLASH_TAG).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1000)

        composeRule.onNodeWithTag(CELEBRATION_FLASH_TAG).assertDoesNotExist()
    }
}
