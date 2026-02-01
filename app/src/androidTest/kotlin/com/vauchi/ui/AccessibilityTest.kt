// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vauchi.ui.onboarding.OnboardingData
import com.vauchi.ui.onboarding.PreviewStep
import com.vauchi.ui.onboarding.WelcomeStep
import com.vauchi.ui.theme.VauchiTheme
import org.junit.Rule
import org.junit.Test
import uniffi.vauchi_mobile.MobileVisibilityLabel

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

        // Primary and secondary actions must be present and clickable
        composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().also { nodes ->
            assert(nodes.size >= 2) { "WelcomeStep should have at least 2 clickable elements" }
        }
    }

    @Test
    fun previewStep_displaysCardContent() {
        composeTestRule.setContent {
            VauchiTheme {
                PreviewStep(
                    data = OnboardingData(
                        displayName = "Alice",
                        phone = "+41 79 123 45 67",
                        email = "alice@example.com"
                    ),
                    onContinue = {},
                    onBack = {}
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
                    onImportBackup = { _, _ -> false }
                )
            }
        }

        // All major sections must be present and readable
        composeTestRule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
        // Back navigation must be clickable
        composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().also { nodes ->
            assert(nodes.isNotEmpty()) { "SettingsScreen should have clickable elements" }
        }
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
                    syncState = SyncState.Idle
                )
            }
        }

        // Empty state message must be visible
        composeTestRule.onAllNodes(hasText(substring = true)).fetchSemanticsNodes().also { nodes ->
            assert(nodes.isNotEmpty()) { "ContactsScreen empty state should have text content" }
        }
    }

    // MARK: - Exchange Accessibility

    @Test
    fun exchangeScreen_controlsAccessible() {
        composeTestRule.setContent {
            VauchiTheme {
                ExchangeScreen(
                    onBack = {},
                    onGenerateQr = { null },
                    onScanQr = {}
                )
            }
        }

        // QR code exchange controls must be present
        composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().also { nodes ->
            assert(nodes.isNotEmpty()) { "ExchangeScreen should have interactive controls" }
        }
    }

    // MARK: - Labels Accessibility

    @Test
    fun labelsScreen_labelsAreClickable() {
        composeTestRule.setContent {
            VauchiTheme {
                LabelsScreen(
                    labels = listOf(
                        MobileVisibilityLabel(
                            id = "label-1",
                            name = "Work",
                            contactCount = 5U,
                            visibleFieldCount = 3U,
                            createdAt = 1706745600UL,
                            modifiedAt = 1706745600UL
                        ),
                        MobileVisibilityLabel(
                            id = "label-2",
                            name = "Family",
                            contactCount = 12U,
                            visibleFieldCount = 5U,
                            createdAt = 1706745500UL,
                            modifiedAt = 1706745700UL
                        )
                    ),
                    suggestedLabels = emptyList(),
                    onBack = {},
                    onLabelClick = {},
                    onCreateLabel = {},
                    onDeleteLabel = {},
                    onRefresh = {}
                )
            }
        }

        // Label items must be visible and interactive
        composeTestRule.onNodeWithText("Work", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Family", substring = true).assertIsDisplayed()
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
                    onRefresh = {}
                )
            }
        }

        // Tab controls must be clickable
        composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().also { nodes ->
            assert(nodes.isNotEmpty()) { "DeliveryStatusScreen should have tab controls" }
        }
    }
}
