// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import app.vauchi.ui.onboarding.OnboardingData
import app.vauchi.ui.onboarding.PreviewStep
import app.vauchi.ui.onboarding.WelcomeStep
import app.vauchi.ui.theme.VauchiTheme
import org.junit.Rule
import org.junit.Test
import uniffi.vauchi_platform.MobileVisibilityLabel

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

    // MARK: - Onboarding Accessibility

    @Test
    fun welcomeStep_buttonsAreClickable() {
        composeTestRule.setContent {
            VauchiTheme {
                WelcomeStep(onContinue = {}, onRestore = {})
            }
        }

        // Value propositions must be visible
        composeTestRule.onNodeWithText("Exchange in person", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-updating", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Private & secure", substring = true).assertIsDisplayed()

        // Primary create button and secondary restore button must be clickable
        // CC-20: assert specific buttons exist, not minimum clickable count
        composeTestRule.onNodeWithContentDescription("setup.create.button").assertHasClickAction()
        composeTestRule.onNodeWithText("Restore from Backup", substring = true).assertHasClickAction()
    }

    @Test
    fun previewStep_displaysCardContent() {
        composeTestRule.setContent {
            VauchiTheme {
                PreviewStep(
                    data =
                        OnboardingData(
                            displayName = "Alice",
                            phone = "+41 79 123 45 67",
                            email = "alice@example.com",
                        ),
                    onContinue = {},
                    onBack = {},
                )
            }
        }

        // Card preview must display the user's data
        composeTestRule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("alice@example.com", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+41 79 123 45 67", substring = true).assertIsDisplayed()
    }

    // MARK: - Settings Accessibility

    @Test
    fun settingsScreen_allSectionsAccessible() {
        composeTestRule.setContent {
            VauchiTheme {
                SettingsScreen(
                    displayName = "Alice",
                    onBack = {},
                    onExportBackup = { "" },
                    onImportBackup = { _, _ -> false },
                )
            }
        }

        // Display name must be visible in the settings header
        composeTestRule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
        // Back button must have accessible content description
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
        // Back button must be clickable
        composeTestRule.onNode(hasContentDescription("Back").and(hasClickAction())).assertExists()
    }

    // MARK: - Contacts Accessibility

    @Test
    fun contactsScreen_emptyStateAccessible() {
        composeTestRule.setContent {
            VauchiTheme {
                ContactsScreen(
                    onBack = {},
                    onListContacts = { emptyList() },
                    onRemoveContact = {},
                    onContactClick = {},
                    syncState = SyncState.Idle,
                )
            }
        }

        // Empty state must describe what to do
        composeTestRule.onNodeWithText("Exchange cards with someone to add contacts", substring = true).assertIsDisplayed()
        // Back button must be accessible
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
        // Empty state container must have semantic description for screen readers
        composeTestRule.onNodeWithContentDescription("No contacts yet", substring = true).assertExists()
    }

    // MARK: - Labels Accessibility

    @Test
    fun labelsScreen_labelsAreClickable() {
        composeTestRule.setContent {
            VauchiTheme {
                LabelsScreen(
                    labels =
                        listOf(
                            MobileVisibilityLabel(
                                id = "label-1",
                                name = "Work",
                                contactCount = 5U,
                                visibleFieldCount = 3U,
                                createdAt = 1706745600UL,
                                modifiedAt = 1706745600UL,
                            ),
                            MobileVisibilityLabel(
                                id = "label-2",
                                name = "Family",
                                contactCount = 12U,
                                visibleFieldCount = 5U,
                                createdAt = 1706745500UL,
                                modifiedAt = 1706745700UL,
                            ),
                        ),
                    suggestedLabels = emptyList(),
                    onBack = {},
                    onLabelClick = {},
                    onCreateLabel = {},
                    onDeleteLabel = {},
                    onRefresh = {},
                )
            }
        }

        // Label items must be visible and interactive
        composeTestRule.onNodeWithText("Work", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Family", substring = true).assertIsDisplayed()
        // Back navigation must be accessible
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
        // Label cards must be clickable for navigation
        composeTestRule.onNode(hasText("Work", substring = true).and(hasClickAction())).assertExists()
        composeTestRule.onNode(hasText("Family", substring = true).and(hasClickAction())).assertExists()
    }

    // MARK: - Delivery Status Accessibility

    @Test
    fun deliveryStatusScreen_emptyStateReadable() {
        composeTestRule.setContent {
            VauchiTheme {
                DeliveryStatusScreen(
                    deliveryRecords = emptyList(),
                    retryEntries = emptyList(),
                    failedCount = 0,
                    isLoading = false,
                    onBack = {},
                    onRetry = {},
                    onRefresh = {},
                )
            }
        }

        // Screen title must be visible
        composeTestRule.onNodeWithText("Delivery Status", substring = true).assertIsDisplayed()
        // Tab labels must be present and clickable
        composeTestRule.onNodeWithText("Recent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pending").assertIsDisplayed()
        // Back navigation must be accessible
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    // MARK: - Core-Driven Accessibility (ScreenRenderer)

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
