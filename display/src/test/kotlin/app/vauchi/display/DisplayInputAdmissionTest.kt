// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Event
import app.vauchi.ui.presentation.PresentationMedia
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — DC-01 malformed native input.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayInputAdmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun invalidUnicodeCannotCrashTheCallbackOrPartiallyActivateASurface() {
        val offered = mutableListOf<Event>()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val media =
            object : PresentationMedia by TestMedia {
                @Composable
                override fun Scanner(
                    accessibilityLabel: String?,
                    onScanned: (String) -> Unit,
                    onPermissionDenied: () -> Unit,
                ) {
                    Button(onClick = { onScanned("\uD800") }) { Text("Malformed native input") }
                }
            }
        AndroidDisplayExecutor(context) { offered.add(it) }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            compose.setContent { DisplayContent(owner, media) }
            compose.runOnIdle { offered.clear() }
            compose.onNodeWithText("Malformed native input").performClick()
            compose.onNodeWithText("Malformed native input").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(emptyList<Event>(), offered)
                assertEquals(DisplaySnapshot(), owner.state.value)
            }
        }
    }
}
