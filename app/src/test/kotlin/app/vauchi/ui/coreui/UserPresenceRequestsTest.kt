// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On the rig the export's OS check cancelled itself: consuming the request
 * restarted the effect waiting on the prompt, so the PIN the user entered
 * was dropped and core heard "cancelled" (vauchi/private#748).
 */
@RunWith(RobolectricTestRunner::class)
class UserPresenceRequestsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val requests = MutableStateFlow<UserPresenceCommand?>(null)
    private val replies = mutableListOf<String>()
    private val userAnswers = CompletableDeferred<String>()

    // @scenario: privacy_compliance :: Without an app password, export uses the phone's own lock
    @Test
    fun `the answer the user gives reaches core after the request is consumed`() {
        composeRule.setContent { UserPresenceRequestsUnderTest() }

        requests.value = CommandDTO.RevealUserPresenceSecret(prompt = "Confirm it's you")
        composeRule.waitForIdle()
        userAnswers.complete(REVEALED)
        composeRule.waitForIdle()

        assertEquals(listOf(REVEALED), replies)
    }

    // @internal
    @Test
    fun `a check torn down with the screen answers cancelled`() {
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) UserPresenceRequestsUnderTest() }
        requests.value = CommandDTO.RevealUserPresenceSecret(prompt = "Confirm it's you")
        composeRule.waitForIdle()

        shown = false
        composeRule.waitForIdle()

        assertEquals(listOf(UserPresenceSecretVault.CANCELLED_EVENT_JSON), replies)
    }

    @Composable
    private fun UserPresenceRequestsUnderTest() {
        UserPresenceRequests(
            requests = requests,
            consume = { requests.value = null },
            reply = { replies += it },
        ) { userAnswers.await() }
    }

    private companion object {
        const val REVEALED = """{"UserPresenceSecretRevealed":{"secret":[42]}}"""
    }
}
