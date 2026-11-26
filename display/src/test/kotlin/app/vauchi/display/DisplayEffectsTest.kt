// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.AlertSpec
import app.vauchi.contract.AnimationToken
import app.vauchi.contract.Command
import app.vauchi.contract.HapticPattern
import app.vauchi.contract.SoundToken
import app.vauchi.contract.ToastSpec
import app.vauchi.ui.presentation.CELEBRATION_FLASH_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature; contact_exchange.feature — native transient feedback.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayEffectsTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun advance(millis: Long = 32) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Test
    fun preparedToastIsVisibleAndRemainsPendingUntilItsDisplayCompletes() {
        compose.mainClock.autoAdvance = false
        AndroidDisplayExecutor(application) { true }.use { owner ->
            prepareDisplay(owner)
            val toast = Command.ShowToast(ToastSpec("Prepared toast"))
            owner.execute(toast)
            compose.setContent { DisplayContent(owner, TestMedia) }
            advance(500)
            compose.onNodeWithText("Prepared toast").assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf(DisplayEffect(1u, toast)), owner.state.value.effects) }
            advance(6000)
            compose.onNodeWithText("Prepared toast").assertDoesNotExist()
            compose.runOnIdle { assertEquals(emptyList<DisplayEffect>(), owner.state.value.effects) }
        }
    }

    @Test
    fun acknowledgingOnePreparedAlertRevealsTheNextWithoutConsumingIt() {
        AndroidDisplayExecutor(application) { true }.use { owner ->
            prepareDisplay(owner)
            owner.execute(Command.PresentAlert(AlertSpec("First message", "First title")))
            val next = Command.PresentAlert(AlertSpec("Second message", "Second title"))
            owner.execute(next)
            compose.setContent { DisplayContent(owner, TestMedia) }
            compose.onNodeWithText("First title").assertIsDisplayed()
            compose.onNodeWithText("First message").assertIsDisplayed()
            compose.onNodeWithText(application.getString(android.R.string.ok)).performClick()
            compose.onNodeWithText("Second title").assertIsDisplayed()
            compose.onNodeWithText("First title").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(DisplayEffect(2u, next)), owner.state.value.effects) }
        }
    }

    @Test
    fun celebrationFlashFinishesItsBeatAndDoesNotRemainOnTheScreen() {
        compose.mainClock.autoAdvance = false
        AndroidDisplayExecutor(application) { true }.use { owner ->
            prepareDisplay(owner)
            compose.setContent { DisplayContent(owner, TestMedia) }
            advance()
            compose.onNodeWithTag(CELEBRATION_FLASH_TAG).assertDoesNotExist()
            compose.runOnIdle { owner.execute(Command.Celebrate(AnimationToken.CardsMeet, HapticPattern.None, SoundToken.None)) }
            advance(200)
            compose.onNodeWithTag(CELEBRATION_FLASH_TAG).assertIsDisplayed()
            advance(1000)
            compose.onNodeWithTag(CELEBRATION_FLASH_TAG).assertDoesNotExist()
            compose.runOnIdle { assertEquals(emptyList<DisplayEffect>(), owner.state.value.effects) }
        }
    }
}
