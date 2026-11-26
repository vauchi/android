// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.vauchi_platform.NoHandle
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.PlatformEventListener

// Feature: generic_presentation_protocol.feature — native command rendering (#1322).
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeupPresentationTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aWakeupToastIsShownWithoutReportingMissingHardware() =
        runTest {
            val engine =
                FakeEngine(
                    """{"notifications":[{"event_key":"prepared-event","title":"Title","body":"Body","contact_id":"contact","os_channel_id":"updates","priority":"Default"}],"commands":[{"ShowToast":{"toast":{"message":"Prepared milestone"}}}]}""",
                )
            val viewModel = CoreAppViewModel(appEngine = engine)

            val outcome = viewModel.onWakeup()
            advanceUntilIdle()

            assertEquals("Prepared milestone", viewModel.toastMessage.value)
            assertEquals(emptyList<String>(), engine.hardwareEvents)
            assertEquals(
                listOf(
                    MobilePendingNotificationDTO(
                        "prepared-event",
                        "Title",
                        "Body",
                        "contact",
                        "updates",
                        MobileNotificationPriorityDTO.Default,
                    ),
                ),
                outcome.notifications,
            )
        }

    @Test
    fun aWakeupAppliesPreparedSurfacesAndNativeEffectsTogether() =
        runTest {
            val engine =
                FakeEngine(
                    """
                    {"notifications":[],"commands":[
                      {"ReplaceSurface":{"surface":{
                        "surface_id":"main","revision":9,"title":"Prepared by Core",
                        "subtitle":null,"accessibility_label":"Prepared by Core","layout":"scroll",
                        "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,
                          "corner_radius":8,"minimum_target_size":44},"nodes":[]
                      }}},
                      {"SetPresentationProfile":{"profile":{
                        "window_class":"compact","pane_layout":"single","primary_surface":"main",
                        "detail_surface":null,"active_surface":"main"
                      }}},
                      {"Celebrate":{"haptic":"success","sound":"none","animation":"cards_meet"}}
                    ]}
                    """.trimIndent(),
                )
            val viewModel = CoreAppViewModel(appEngine = engine)

            viewModel.onWakeup()
            advanceUntilIdle()

            assertEquals(
                9uL,
                viewModel.presentationState.value.surfaces["main"]
                    ?.revision,
            )
            assertEquals(
                "Prepared by Core",
                viewModel.presentationState.value.surfaces["main"]
                    ?.title,
            )
            assertEquals(
                "main",
                viewModel.presentationState.value.profile
                    ?.activeSurface,
            )
            assertEquals(CelebrateRequest("success", "none", "cards_meet"), viewModel.celebrateRequest.value)
            assertEquals(emptyList<String>(), engine.hardwareEvents)
        }

    private class FakeEngine(
        private val wakeup: String,
    ) : PlatformAppEngine(NoHandle) {
        val hardwareEvents = mutableListOf<String>()

        override fun initialCommandsJson(): String = """{"commands":[]}"""

        override fun onWakeup(): String = wakeup

        override fun setEventListener(listener: PlatformEventListener) = Unit

        override fun dispatchJson(eventJson: String): String {
            hardwareEvents.add(eventJson)
            return """{"commands":[]}"""
        }
    }
}
