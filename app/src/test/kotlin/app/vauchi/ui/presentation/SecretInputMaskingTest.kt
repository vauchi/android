// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * Core sends the app password and the duress PIN as `password` and `pin`
 * inputs. They must not be drawn as typed: whoever stands next to the
 * person, a coercer included, would read them (vauchi/private#619).
 */
@RunWith(RobolectricTestRunner::class)
class SecretInputMaskingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(inputKind: String) {
        composeRule.setContent {
            MaterialTheme {
                PresentationNodeRenderer(
                    surfaceId = "lock",
                    node =
                        PresentationNode.Input(
                            bindingId = "surface.1.pin",
                            label = "Password",
                            value = "",
                            placeholder = null,
                            inputKind = inputKind,
                            maxLength = 128,
                            validationError = null,
                            enabled = true,
                            accessibility = AccessibilitySpec("Password entry", null),
                        ),
                    onEvent = {},
                    onCameraPermissionDenied = {},
                )
            }
        }
    }

    private fun typeIntoTheField(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun `a password input does not show what was typed`() {
        render("password")

        typeIntoTheField("pass1234")

        composeRule.onNodeWithText("pass1234").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun `a pin input does not show what was typed`() {
        render("pin")

        typeIntoTheField("654321")

        composeRule.onNodeWithText("654321").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun `a text input still shows what was typed`() {
        render("text")

        typeIntoTheField("Alice")

        composeRule.onNodeWithText("Alice").assertExists()
        composeRule.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }
}
