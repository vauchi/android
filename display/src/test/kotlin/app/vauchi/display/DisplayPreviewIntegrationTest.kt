// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import android.view.SurfaceView
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.ui.presentation.PresentationMedia
import app.vauchi.ui.presentation.QrPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

// Feature: generic_presentation_protocol.feature — neutral preview ownership.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DisplayPreviewIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class Host : PreviewSurfaceHost {
        val events = mutableListOf<String>()
        val views = mutableListOf<SurfaceView>()

        override fun attach(
            previewId: String,
            view: SurfaceView,
        ): AutoCloseable {
            events.add("attach:$previewId")
            views.add(view)
            return AutoCloseable { events.add("close:$previewId") }
        }
    }

    @Test
    fun aMissingPreparedViewportWaitsWithoutInvokingTheLegacyScanner() {
        val host = Host()
        AndroidDisplayExecutor(context) { true }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            compose.setContent { DisplayContent(owner, TestMedia, previewHost = host) }
            compose.onNodeWithText("Prepared title").assertIsDisplayed()
            compose.onNodeWithText("Native scan").assertDoesNotExist()
            compose.onNodeWithText("Native denial").assertDoesNotExist()
            compose.runOnIdle { assertEquals(emptyList<String>(), host.events) }
        }
    }

    @Test
    fun preparedViewportReplacementAndSurfaceRemovalOwnExactlyTheMatchingNativeLeases() {
        val host = Host()
        AndroidDisplayExecutor(context) { true }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("opaque-preview-1"))
            compose.setContent {
                DisplayContent(owner, TestMedia, Modifier.requiredSize(320.dp, 480.dp), previewHost = host)
            }
            compose.onNodeWithText("Prepared title").assertIsDisplayed()
            compose.onNodeWithText("Native scan").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("attach:opaque-preview-1"), host.events)
                val view = host.views.single()
                assertTrue("neutral viewport must have visible native bounds", view.width > 0 && view.height > 0)
                assertTrue("neutral viewport keeps the existing portrait 3:4 frame", abs(view.width * 4 - view.height * 3) <= 3)
                owner.execute(Command.CameraViewport("opaque-preview-2"))
            }
            compose.runOnIdle {
                assertEquals(listOf("attach:opaque-preview-1", "close:opaque-preview-1", "attach:opaque-preview-2"), host.events)
                owner.execute(displaySurface(2u, "Preview removed"))
            }
            compose.onNodeWithText("Preview removed").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(
                    listOf("attach:opaque-preview-1", "close:opaque-preview-1", "attach:opaque-preview-2", "close:opaque-preview-2"),
                    host.events,
                )
                owner.close()
            }
            compose.runOnIdle { assertEquals(4, host.events.size) }
        }
    }

    @Test
    fun replacingTheDisplayOwnerReleasesItsViewEvenWhenTheOpaqueViewportIdIsReused() {
        val host = Host()
        AndroidDisplayExecutor(context) { true }.use { first ->
            AndroidDisplayExecutor(context) { true }.use { second ->
                for (owner in listOf(first, second)) {
                    prepareDisplay(owner, CAPTURE_NODE)
                    owner.execute(Command.CameraViewport("same-opaque-id"))
                }
                val current = mutableStateOf(first)
                compose.setContent { DisplayContent(current.value, TestMedia, previewHost = host) }
                compose.runOnIdle {
                    assertEquals(listOf("attach:same-opaque-id"), host.events)
                    current.value = second
                }
                compose.runOnIdle {
                    assertEquals(listOf("attach:same-opaque-id", "close:same-opaque-id", "attach:same-opaque-id"), host.events)
                    first.close()
                }
                compose.runOnIdle { assertEquals(3, host.events.size) }
                compose.onNodeWithText("Prepared title").assertIsDisplayed()
                compose.runOnIdle { second.close() }
                compose.runOnIdle {
                    assertEquals(
                        listOf("attach:same-opaque-id", "close:same-opaque-id", "attach:same-opaque-id", "close:same-opaque-id"),
                        host.events,
                    )
                }
            }
        }
    }

    @Test
    fun changingOnlyTheCodeAttachmentDoesNotRestartTheNativePreview() {
        val host = Host()
        val media = mutableStateOf<PresentationMedia>(TestMedia)
        AndroidDisplayExecutor(context) { true }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("stable-preview"))
            compose.setContent { DisplayContent(owner, media.value, previewHost = host) }
            compose.runOnIdle {
                assertEquals(listOf("attach:stable-preview"), host.events)
                media.value = object : PresentationMedia by TestMedia {}
            }
            compose.runOnIdle { assertEquals(listOf("attach:stable-preview"), host.events) }
        }
    }

    @Test
    fun aNativeAttachmentFailureReportsOnlyAFixedTypedErrorAndLeavesThePreparedScreen() {
        val events = mutableListOf<Event>()
        val host = PreviewSurfaceHost { _, _ -> throw IllegalStateException("private native detail") }
        AndroidDisplayExecutor(context) { events.add(it) }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("opaque-preview"))
            compose.setContent { DisplayContent(owner, TestMedia, previewHost = host) }
            compose.onNodeWithText("Prepared title").assertIsDisplayed()
            compose.onNodeWithText("Native scan").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(
                    listOf(Event.HardwareError("camera_preview_failed", "camera")),
                    events.filterNot {
                        it is Event.PresentationEnvironmentChanged
                    },
                )
                assertEquals("opaque-preview", owner.state.value.previewId)
            }
        }
    }

    @Test
    fun aRefusedNativeFailureClosesAdmissionWithoutThrowingOrRetainingThePreparedUi() {
        val events = mutableListOf<Event>()
        val host = PreviewSurfaceHost { _, _ -> throw IllegalStateException("private native detail") }
        AndroidDisplayExecutor(context) { event ->
            events.add(event)
            event !is Event.HardwareError
        }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("opaque-preview"))
            compose.setContent { DisplayContent(owner, TestMedia, previewHost = host) }
            compose.onNodeWithText("Prepared title").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(
                    listOf(Event.HardwareError("camera_preview_failed", "camera")),
                    events.filterNot {
                        it is Event.PresentationEnvironmentChanged
                    },
                )
                assertEquals(DisplaySnapshot(), owner.state.value)
                assertFalse(owner.execute(Command.CameraViewport("late-preview")))
            }
        }
    }

    @Test
    fun cleanupFailureDoesNotPreventViewportReplacementOrEscapeOwnerClosure() {
        val events = mutableListOf<Event>()
        val native = mutableListOf<String>()
        val host =
            PreviewSurfaceHost { id, _ ->
                native.add("attach:$id")
                AutoCloseable {
                    native.add("close:$id")
                    throw IllegalStateException("private cleanup detail")
                }
            }
        AndroidDisplayExecutor(context) { events.add(it) }.use { owner ->
            prepareDisplay(owner, CAPTURE_NODE)
            owner.execute(Command.CameraViewport("first"))
            compose.setContent { DisplayContent(owner, TestMedia, previewHost = host) }
            compose.runOnIdle { owner.execute(Command.CameraViewport("second")) }
            compose.runOnIdle {
                assertEquals(listOf("attach:first", "close:first", "attach:second"), native)
                assertEquals(
                    listOf(Event.HardwareError("camera_preview_cleanup_failed", "camera")),
                    events.filterNot {
                        it is Event.PresentationEnvironmentChanged
                    },
                )
                owner.close()
            }
            compose.runOnIdle {
                assertEquals(listOf("attach:first", "close:first", "attach:second", "close:second"), native)
                assertEquals(
                    listOf(Event.HardwareError("camera_preview_cleanup_failed", "camera")),
                    events.filterNot {
                        it is Event.PresentationEnvironmentChanged
                    },
                )
                assertEquals(DisplaySnapshot(), owner.state.value)
            }
        }
    }

    @Test
    fun nativePreviewHostingDoesNotAlterCodeDrawingArgumentsOrAcquireAPreviewForDisplayCodes() {
        val host = Host()
        val observed = mutableListOf<List<Any?>>()
        val media =
            object : PresentationMedia by TestMedia {
                @Composable
                override fun Code(
                    data: String,
                    accessibilityLabel: String?,
                    placement: QrPlacement?,
                    errorCorrection: String?,
                    modifier: Modifier,
                ) {
                    observed.add(listOf(data, accessibilityLabel, placement, errorCorrection))
                    TestMedia.Code(data, accessibilityLabel, placement, errorCorrection, modifier)
                }
            }
        AndroidDisplayExecutor(context) { true }.use { owner ->
            prepareDisplay(owner, DISPLAY_NODE)
            owner.execute(Command.CameraViewport("not-a-capture"))
            compose.setContent { DisplayContent(owner, media, previewHost = host) }
            compose.onNodeWithText("CORE-CODE").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf("CORE-CODE", "Prepared code", QrPlacement(650, 100, 200), "low"), observed.last())
                assertEquals(emptyList<String>(), host.events)
            }
        }
    }
}

private const val DISPLAY_NODE = """[{"Qr":{"id":"opaque-code","purpose":"display","payloads":["CORE-CODE"],
    "size":"compact","error_correction":"low","placement":{"size":650,"x":100,"y":200},
    "accessibility":{"label":"Prepared code","hint":null,"role":null}}}]"""
