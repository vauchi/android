// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vauchi.ui.DeliveryStatusScreen
import com.vauchi.ui.ExchangeScreen
import com.vauchi.ui.ContactsScreen
import com.vauchi.ui.LabelsScreen
import com.vauchi.ui.SettingsScreen
import com.vauchi.ui.SyncState
import com.vauchi.ui.onboarding.AddFieldsStep
import com.vauchi.ui.onboarding.CreateIdentityStep
import com.vauchi.ui.onboarding.OnboardingData
import com.vauchi.ui.onboarding.PreviewStep
import com.vauchi.ui.onboarding.ReadyStep
import com.vauchi.ui.onboarding.SecurityStep
import com.vauchi.ui.onboarding.WelcomeStep
import com.vauchi.ui.theme.VauchiTheme
import uniffi.vauchi_mobile.MobileDeliveryRecord
import uniffi.vauchi_mobile.MobileDeliveryStatus
import uniffi.vauchi_mobile.MobileRetryEntry
import uniffi.vauchi_mobile.MobileVisibilityLabel

// =============================================================
// Onboarding Steps
// =============================================================

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun WelcomeStepScreenshot() {
    VauchiTheme {
        WelcomeStep(onContinue = {}, onRestore = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun CreateIdentityStepEmptyScreenshot() {
    VauchiTheme {
        CreateIdentityStep(
            displayName = "",
            onDisplayNameChange = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun CreateIdentityStepFilledScreenshot() {
    VauchiTheme {
        CreateIdentityStep(
            displayName = "Alice",
            onDisplayNameChange = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun AddFieldsStepScreenshot() {
    VauchiTheme {
        AddFieldsStep(
            phone = "",
            email = "",
            onPhoneChange = {},
            onEmailChange = {},
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun PreviewStepScreenshot() {
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

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun SecurityStepScreenshot() {
    VauchiTheme {
        SecurityStep(onContinue = {}, onBack = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun ReadyStepScreenshot() {
    VauchiTheme {
        ReadyStep()
    }
}

// =============================================================
// Main Screens
// =============================================================

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun SettingsScreenScreenshot() {
    VauchiTheme {
        SettingsScreen(
            displayName = "Alice",
            onBack = {},
            onExportBackup = { "" },
            onImportBackup = { _, _ -> false }
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun ContactsScreenScreenshot() {
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

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun ExchangeScreenScreenshot() {
    VauchiTheme {
        ExchangeScreen(
            onBack = {},
            onGenerateQr = { null },
            onScanQr = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun DeliveryStatusEmptyScreenshot() {
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

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun DeliveryStatusWithRecordsScreenshot() {
    VauchiTheme {
        DeliveryStatusScreen(
            deliveryRecords = listOf(
                MobileDeliveryRecord(
                    messageId = "msg-1",
                    recipientId = "recipient-1",
                    status = MobileDeliveryStatus.DELIVERED,
                    errorReason = null,
                    createdAt = 1706745600UL,
                    updatedAt = 1706745700UL,
                    expiresAt = null
                ),
                MobileDeliveryRecord(
                    messageId = "msg-2",
                    recipientId = "recipient-2",
                    status = MobileDeliveryStatus.FAILED,
                    errorReason = "Recipient offline",
                    createdAt = 1706745500UL,
                    updatedAt = 1706745600UL,
                    expiresAt = null
                )
            ),
            retryEntries = listOf(
                MobileRetryEntry(
                    messageId = "msg-2",
                    recipientId = "recipient-2",
                    attempt = 2U,
                    nextRetry = 1706746000UL,
                    createdAt = 1706745500UL,
                    maxAttempts = 5U,
                    isMaxExceeded = false
                )
            ),
            failedCount = 1,
            isLoading = false,
            onBack = {},
            onRetry = {},
            onRefresh = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun LabelsScreenEmptyScreenshot() {
    VauchiTheme {
        LabelsScreen(
            labels = emptyList(),
            suggestedLabels = listOf("Work", "Family", "Friends"),
            onBack = {},
            onLabelClick = {},
            onCreateLabel = {},
            onDeleteLabel = {},
            onRefresh = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true)
@Composable
fun LabelsScreenWithLabelsScreenshot() {
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
                ),
                MobileVisibilityLabel(
                    id = "label-3",
                    name = "Friends",
                    contactCount = 8U,
                    visibleFieldCount = 4U,
                    createdAt = 1706745400UL,
                    modifiedAt = 1706745800UL
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
