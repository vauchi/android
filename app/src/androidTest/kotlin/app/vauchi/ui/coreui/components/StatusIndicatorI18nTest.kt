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
 * verbatim, and while the vauchi-platform pin predates that field the
 * fallback resolves the `status.*` catalog key. Rendering under a
 * non-English locale is the only observable difference between the
 * catalog path and a hardcoded literal — in English they produce
 * identical text.
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

    @Test
    fun status_badge_without_wire_label_falls_back_to_locale_catalog() {
        composeTestRule.setContent {
            VauchiTheme {
                StatusIndicatorComponent(
                    icon = null,
                    title = "probe-title",
                    detail = null,
                    status = Status.Success,
                    statusLabel = null,
                )
            }
        }

        composeTestRule
            .onNodeWithText("Erfolg")
            .assertExists(
                "Pre-status_label fallback did not resolve status.success from " +
                    "the catalog under the German locale — label is not routed through t()",
            )
        composeTestRule.onNodeWithText("Success").assertDoesNotExist()
    }
}
