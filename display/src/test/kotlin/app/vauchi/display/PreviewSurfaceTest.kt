// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreviewSurfaceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun previewIdsAndDisposalControlTheHostAttachmentLifetime() {
        val previewId = mutableStateOf<String?>("opaque-camera-1")
        val observed = mutableListOf<String>()
        val host =
            PreviewSurfaceHost { id, _ ->
                observed.add("attach:$id")
                AutoCloseable { observed.add("close:$id") }
            }
        composeRule.setContent {
            Box(Modifier.size(100.dp)) {
                previewId.value?.let { id -> PreviewSurface(id, "Camera preview", host) }
            }
        }

        composeRule.onNodeWithContentDescription("Camera preview").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("attach:opaque-camera-1"), observed) }
        composeRule.runOnIdle { previewId.value = "opaque-camera-2" }
        composeRule.runOnIdle {
            assertEquals(listOf("attach:opaque-camera-1", "close:opaque-camera-1", "attach:opaque-camera-2"), observed)
        }
        composeRule.runOnIdle { previewId.value = null }
        composeRule.runOnIdle {
            assertEquals(
                listOf("attach:opaque-camera-1", "close:opaque-camera-1", "attach:opaque-camera-2", "close:opaque-camera-2"),
                observed,
            )
        }
        composeRule.onNodeWithContentDescription("Camera preview").assertDoesNotExist()
    }
}
