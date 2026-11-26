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
import app.vauchi.contract.ContractJson
import app.vauchi.contract.Event
import app.vauchi.contract.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — native overlay dismissal acknowledgement.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayOverlayDismissalTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun acceptingTheDismissalEventNeedsNoCommandToRemoveTheNativeOverlay() {
        val events = mutableListOf<Event>()
        AndroidDisplayExecutor(ApplicationProvider.getApplicationContext<Application>()) { events.add(it) }.use { owner ->
            prepareDisplay(owner)
            owner.execute(
                ContractJson.decodeCommand(
                    """{"PresentOverlay":{"surface_id":"opaque-surface","revision":1,
                    "overlay":{"kind":"navigation","title":"Prepared destinations","close_label":"Prepared close","items":[]}}}""",
                ),
            )
            compose.setContent { DisplayContent(owner, TestMedia) }
            compose.onNodeWithText("Prepared destinations").assertIsDisplayed()
            compose.runOnIdle { events.clear() }
            compose.onNodeWithTag("overlay.close").performClick()
            compose.onNodeWithText("Prepared destinations").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf(Event.OverlayDismissed(OverlayKind.Navigation, "opaque-surface")), events)
                assertNull(owner.state.value.presentation.activeOverlay)
            }
        }
    }
}
