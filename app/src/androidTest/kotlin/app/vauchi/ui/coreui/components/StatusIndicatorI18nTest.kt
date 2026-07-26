// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import app.vauchi.ui.coreui.Status
import app.vauchi.ui.theme.VauchiTheme
import app.vauchi.util.LocalizationManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The status badge label must never be a hardcoded English literal
 * (ADR-038): the core-resolved wire label (`status_label`) renders
 * verbatim. Rendering under a non-English locale (German here) is what
 * makes the assertion meaningful — a hardcoded English literal would
 * still read "Success", so the German-locale run proves the badge shows
 * the core-provided value and nothing else.
 *
 * The pre-`status_label` catalog fallback that older tests exercised is
 * gone: the current binding always supplies `status_label` and the
 * component requires it non-null, so the fallback path no longer exists
 * to test (CC-24 — behaviour removed by the binding upgrade; the
 * verbatim-render contract below is the surviving coverage).
 */
class StatusIndicatorI18nTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var localizationManager: LocalizationManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        localizationManager = LocalizationManager.getInstance(context)
        localizationManager.selectLocale("de")
    }

    @After
    fun tearDown() {
        // The manager is a process-wide singleton persisting to prefs —
        // leave it as other tests expect it.
        localizationManager.resetToSystem()
    }

    @Test
    fun status_badge_renders_core_resolved_wire_label_verbatim() {
        composeTestRule.setContent {
            VauchiTheme {
                StatusIndicatorComponent(
                    icon = null,
                    title = "probe-title",
                    detail = null,
                    status = Status.Success,
                    statusLabel = "Wire label probe 9c2a",
                )
            }
        }

        composeTestRule
            .onNodeWithText("Wire label probe 9c2a")
            .assertExists(
                "Status badge did not render the core-resolved status_label verbatim",
            )
        composeTestRule.onNodeWithText("Success").assertDoesNotExist()
    }
}
