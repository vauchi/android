// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — isolate native edit state between display owners.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayOwnerIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localTextEditsCannotFollowOpaqueBindingsIntoANewDisplayOwner() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidDisplayExecutor(context) { true }.use { first ->
            AndroidDisplayExecutor(context) { true }.use { second ->
                for (owner in listOf(first, second)) prepareDisplay(owner, INPUT_NODE)
                val current = mutableStateOf(first)
                compose.setContent { DisplayContent(current.value, TestMedia) }
                compose.onNodeWithContentDescription("Prepared input").performTextReplacement("local private edit")
                compose.onNodeWithContentDescription("Prepared input").assertTextContains("local private edit")
                compose.runOnIdle { current.value = second }
                compose.onNodeWithContentDescription("Prepared input").assertTextContains("Core initial")
                compose.onNodeWithText("local private edit").assertDoesNotExist()
            }
        }
    }
}

private const val INPUT_NODE = """[{"Input":{"binding_id":"same-opaque-binding","input_kind":"text",
    "label":"Prepared input","value":"Core initial","enabled":true,"max_length":128,
    "accessibility":{"label":"Prepared input","hint":null,"role":null}}}]"""
