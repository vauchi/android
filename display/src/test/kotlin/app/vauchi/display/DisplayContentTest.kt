// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.InputValue
import app.vauchi.contract.ToastSpec
import app.vauchi.ui.presentation.PresentationMedia
import app.vauchi.ui.presentation.QrPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — prepared presentation and opaque events.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayContentTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun preparedCommandsRenderAndUpdateWithoutAnAppViewModel() {
        val events = mutableListOf<Event>()
        AndroidDisplayExecutor(application) { events.add(it) }.use { owner ->
            prepareDisplay(owner)
            compose.setContent { DisplayContent(owner, TestMedia) }
            compose.onNodeWithText("Prepared title").assertIsDisplayed()
            compose.runOnIdle { events.clear() }
            compose.onNodeWithText("Prepared action").performClick()
            compose.runOnIdle {
                assertEquals(
                    listOf(Event.SurfaceActivated("opaque-surface"), Event.ActionActivated("opaque-action", "opaque-surface")),
                    events,
                )
                owner.execute(displaySurface(2u, "Updated title"))
            }
            compose.onNodeWithText("Updated title").assertIsDisplayed()
            compose.onNodeWithText("Prepared title").assertDoesNotExist()
        }
    }

    @Test
    fun nativeMediaReportsExactOpaqueTextAndPermissionDenialThroughTheTypedOwner() {
        val events = mutableListOf<Event>()
        AndroidDisplayExecutor(application) { events.add(it) }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            compose.setContent { DisplayContent(owner, TestMedia) }
            compose.runOnIdle { events.clear() }
            compose.onNodeWithText("Native scan").performClick()
            compose.onNodeWithText("Native denial").performClick()
            compose.runOnIdle {
                assertEquals(
                    listOf(
                        Event.SurfaceActivated("opaque-surface"),
                        Event.ValueChanged("opaque-input", "opaque-surface", InputValue.Text("opaque 🦊 payload")),
                        Event.PermissionDenied("camera"),
                    ),
                    events,
                )
            }
        }
    }

    @Test
    fun refusedActivationClosesAdmissionBeforeTheFollowingActionCanEscape() {
        val offered = mutableListOf<Event>()
        var refuse = false
        AndroidDisplayExecutor(application) { event ->
            offered.add(event)
            !refuse
        }.use { owner ->
            prepareDisplay(owner)
            compose.setContent { DisplayContent(owner, TestMedia) }
            compose.onNodeWithText("Prepared action").assertIsDisplayed()
            compose.runOnIdle {
                offered.clear()
                refuse = true
            }
            compose.onNodeWithText("Prepared action").performClick()
            compose.onNodeWithText("Prepared action").performClick()
            compose.runOnIdle {
                assertEquals(listOf(Event.SurfaceActivated("opaque-surface")), offered)
                assertFalse(owner.execute(Command.ShowToast(ToastSpec("not admitted"))))
            }
        }
    }
}

internal object TestMedia : PresentationMedia {
    @Composable
    override fun Scanner(
        accessibilityLabel: String?,
        onScanned: (String) -> Unit,
        onPermissionDenied: () -> Unit,
    ) {
        Column {
            Button(onClick = { onScanned("opaque 🦊 payload") }) { Text("Native scan") }
            Button(onClick = onPermissionDenied) { Text("Native denial") }
        }
    }

    @Composable
    override fun Code(
        data: String,
        accessibilityLabel: String?,
        placement: QrPlacement?,
        errorCorrection: String?,
        modifier: Modifier,
    ) {
        Text(data, modifier)
    }
}

internal const val CAPTURE_NODE = """[{"Qr":{"id":"opaque-input","purpose":"capture","payloads":[],
    "accessibility":{"label":"Prepared scanner","hint":null,"role":null}}}]"""
