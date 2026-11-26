// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.ColorScheme
import app.vauchi.contract.Event
import app.vauchi.contract.InputMode
import app.vauchi.contract.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Feature: generic_presentation_protocol.feature — native environment and Back observations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-420dpi-qwerty")
class DisplayObservationsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun nativeSizeInputModesAppearanceAndReducedMotionReachTheTypedBoundary() {
        val original = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val events = mutableListOf<Event>()
        try {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            AndroidDisplayExecutor(context) { events.add(it) }.use { owner ->
                prepareDisplay(owner)
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent { DisplayContent(owner, TestMedia, Modifier.requiredSize(320.dp, 480.dp)) }
                    }
                    compose.onNodeWithText("Prepared title").assertIsDisplayed()
                    compose.runOnIdle {
                        assertEquals(
                            listOf(
                                Event.PresentationEnvironmentChanged(
                                    480u,
                                    320u,
                                    ColorScheme.Dark,
                                    listOf(InputMode.Touch, InputMode.Keyboard),
                                    MotionPreference.Reduced,
                                    true,
                                ),
                            ),
                            events,
                        )
                    }
                }
            }
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, original)
        }
    }

    @Test
    fun nativeBackReportsTheActiveOpaqueSurfaceWithoutCallingCore() {
        val events = mutableListOf<Event>()
        AndroidDisplayExecutor(context) { events.add(it) }.use { owner ->
            prepareDisplay(owner)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { DisplayContent(owner, TestMedia) } }
                compose.onNodeWithText("Prepared title").assertIsDisplayed()
                compose.runOnIdle { events.clear() }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.runOnIdle { assertEquals(listOf(Event.BackRequested("opaque-surface")), events) }
            }
        }
    }
}
