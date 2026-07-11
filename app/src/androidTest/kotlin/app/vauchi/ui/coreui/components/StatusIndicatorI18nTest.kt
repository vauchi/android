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
 * The status badge label must come from the locale catalog (`status.*`
 * keys, ADR-038), not a hardcoded English literal. Rendering under a
 * non-English locale is the only observable difference between the two
 * paths — in English they produce identical text.
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
    fun status_badge_label_follows_locale_catalog() {
        composeTestRule.setContent {
            VauchiTheme {
                StatusIndicatorComponent(
                    icon = null,
                    title = "probe-title",
                    detail = null,
                    status = Status.Success,
                )
            }
        }

        composeTestRule
            .onNodeWithText("Erfolg")
            .assertExists(
                "Status badge did not render the catalog label for status.success " +
                    "under the German locale — label is not routed through t()",
            )
        composeTestRule.onNodeWithText("Success").assertDoesNotExist()
    }
}
