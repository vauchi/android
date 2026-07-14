// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.ActionListItem
import app.vauchi.ui.coreui.Component
import app.vauchi.ui.coreui.ScreenModel
import app.vauchi.ui.coreui.ScreenRenderer
import app.vauchi.ui.coreui.Status
import app.vauchi.ui.theme.VauchiTheme
import org.junit.Rule
import org.junit.Test

/**
 * Accessibility tests for Compose UI screens.
 *
 * Verifies that:
 * - Interactive elements are reachable and actionable
 * - Key screens render readable text content
 * - Buttons and controls have proper semantics
 *
 * Based on: features/accessibility.feature (WCAG 2.1 AA)
 */
class AccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // `settingsScreen_allSectionsAccessible` was removed alongside the native
    // `SettingsScreen` composable: `Screen.Settings` routes through
    // `CoreScreenView("Settings")`, so the accessibility guarantees (back
    // button, section headings) now live on core's Settings engine and are
    // covered by `test:reachability` + the cross-platform ScreenRenderer
    // a11y wiring.

    // `contactsScreen_emptyStateAccessible` was removed in Phase 1B.2:
    // Screen.Contacts now renders through CoreScreenView("Contacts").
    // The equivalent accessibility guarantees (empty-state copy, back
    // button, "No contacts yet" heading) live on core's
    // ContactListEngine and are covered by
    // `core/vauchi-app/src/ui/contact_list.rs` unit tests plus the
    // reachability walker in `test:reachability`.

    // `labelsScreen_labelsAreClickable` removed in the 2026-04-28 Pure
    // Humble UI retirement (Pair 2): `Screen.Labels` already renders
    // through `CoreScreenView("Groups")` against core's `GroupsEngine`,
    // which emits per-row a11y labels via `Component.ListItem.a11y`. The
    // core-driven accessibility contract is exercised by
    // `coreScreenView_appliesA11yLabelsFromModel` below and by
    // `core/vauchi-app/tests/reachability/groups.rs`.

    // Note: native DeliveryStatusScreen accessibility tests removed in
    // 2026-04-28 Pure Humble UI retirement. The Android wrapper itself
    // was retired in `vauchi/android!371` (Phase 4a residual) — it had
    // zero callers and was never wired into `coreScreenIdToVariant`,
    // so the screen is unreachable from Android until a frontend route
    // revives it. Core-side a11y for `DeliveryStatusEngine` continues
    // to be exercised by `coreScreenView_appliesA11yLabelsFromModel`
    // below and by `core/vauchi-app/tests/reachability/delivery_status.rs`.

    @Test
    fun coreScreenView_appliesA11yLabelsFromModel() {
        val model =
            ScreenModel(
                screenId = "test",
                title = "Test Screen",
                components =
                    listOf(
                        Component.StatusIndicator(
                            id = "status1",
                            icon = null,
                            title = "Connection",
                            detail = "Connected",
                            status = Status.Success,
                            statusLabel = "Connected",
                            a11y =
                                A11y(
                                    label = "Connection status: connected",
                                    hint = "Shows current relay connection state",
                                ),
                        ),
                    ),
                actions = emptyList(),
            )

        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = model, onAction = {})
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Connection status: connected")
            .assertIsDisplayed()
    }

    @Test
    fun coreScreenView_actionListItemsHaveA11y() {
        val model =
            ScreenModel(
                screenId = "test",
                title = "Test",
                components =
                    listOf(
                        Component.ActionList(
                            id = "actions",
                            items =
                                listOf(
                                    ActionListItem(
                                        id = "share",
                                        label = "Share",
                                        icon = null,
                                        detail = null,
                                        a11y = A11y(label = "Share contact card"),
                                    ),
                                ),
                        ),
                    ),
                actions = emptyList(),
            )

        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = model, onAction = {})
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Share contact card")
            .assertIsDisplayed()
            .assertHasClickAction()
    }
}
