// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ceremony's one-beat flash is on screen exactly while the host says
 * it is celebrating, and composes nothing otherwise (vauchi/private#276).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class CelebrationFlashTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var visible by mutableStateOf(false)

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                CelebrationFlash(visible = visible, tokens = DefaultPresentationTokens)
            }
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun theFlashIsDrawnWhileCelebrating() {
        visible = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CELEBRATION_FLASH_TAG).assertIsDisplayed()
    }

    @Test
    fun nothingIsComposedWhenNotCelebrating() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CELEBRATION_FLASH_TAG).assertDoesNotExist()
    }
}
