// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — no stale native acquisition across owner handoff.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayPreviewHandoffTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aNewEmptyOwnerNeverAttachesThePreviousOwnersPreparedViewport() {
        val native = mutableListOf<String>()
        val host =
            PreviewSurfaceHost { id, _ ->
                native.add("attach:$id")
                AutoCloseable { native.add("close:$id") }
            }
        AndroidDisplayExecutor(context) { true }.use { first ->
            AndroidDisplayExecutor(context) { true }.use { second ->
                prepareDisplay(first, CAPTURE_NODE)
                first.execute(Command.CameraViewport("first-owners-preview"))
                val current = mutableStateOf(first)
                compose.setContent { DisplayContent(current.value, TestMedia, previewHost = host) }
                compose.onNodeWithText("Prepared title").assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(listOf("attach:first-owners-preview"), native)
                    current.value = second
                }
                compose.onNodeWithText("Prepared title").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(listOf("attach:first-owners-preview", "close:first-owners-preview"), native)
                    assertEquals(DisplaySnapshot(), second.state.value)
                }
            }
        }
    }

    @Test
    fun refusingACleanupFailurePreventsAnAlreadyScheduledReplacementFromAcquiringANativeView() {
        val native = mutableListOf<String>()
        val offered = mutableListOf<Event>()
        val host =
            PreviewSurfaceHost { id, _ ->
                native.add("attach:$id")
                AutoCloseable {
                    native.add("close:$id")
                    if (id == "first") throw IllegalStateException("private cleanup detail")
                }
            }
        AndroidDisplayExecutor(context) { event ->
            offered.add(event)
            event !is Event.HardwareError
        }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("first"))
            compose.setContent { DisplayContent(owner, TestMedia, previewHost = host) }
            compose.onNodeWithText("Prepared title").assertIsDisplayed()
            compose.runOnIdle { owner.execute(Command.CameraViewport("second")) }
            compose.onNodeWithText("Prepared title").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("attach:first", "close:first"), native)
                assertEquals(
                    listOf(Event.HardwareError("camera_preview_cleanup_failed", "camera")),
                    offered.filterNot {
                        it is Event.PresentationEnvironmentChanged
                    },
                )
                assertEquals(DisplaySnapshot(), owner.state.value)
            }
        }
    }
}
