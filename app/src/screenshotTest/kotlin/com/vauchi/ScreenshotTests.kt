// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import uniffi.vauchi_mobile.MobileContactCard
import uniffi.vauchi_mobile.MobileContactField
import uniffi.vauchi_mobile.MobileFieldType
import java.util.Locale

// VRT device spec: 360dp wide, 800dp tall, xhdpi (2x) = 720×1600 px.
// Large enough to catch layout issues, small enough to keep baselines under 60 KB each.
// Using xhdpi (320dpi/2x) instead of default xxhdpi (480dpi/3x) reduces file size by ~55%.
private const val VRT_DEVICE = "spec:width=360dp,height=800dp,dpi=320"

// =============================================================
// Onboarding Steps - Light Theme
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun WelcomeStepScreenshot() {
    VauchiTheme(dynamicColor = false) {
        WelcomeStep(onContinue = {}, onRestore = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun CreateIdentityStepEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
        CreateIdentityStep(
            displayName = "",
            onDisplayNameChange = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun CreateIdentityStepFilledScreenshot() {
    VauchiTheme(dynamicColor = false) {
        CreateIdentityStep(
            displayName = "Alice",
            onDisplayNameChange = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun AddFieldsStepScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun AddFieldsStepFilledScreenshot() {
    VauchiTheme(dynamicColor = false) {
        AddFieldsStep(
            phone = "+41 79 123 45 67",
            email = "alice@example.com",
            onPhoneChange = {},
            onEmailChange = {},
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewStepScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun SecurityStepScreenshot() {
    VauchiTheme(dynamicColor = false) {
        SecurityStep(onContinue = {}, onBack = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ReadyStepScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ReadyStep()
    }
}

// =============================================================
// Main Screens - Light Theme
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun SettingsScreenScreenshot() {
    VauchiTheme(dynamicColor = false) {
        SettingsScreen(
            displayName = "Alice",
            onBack = {},
            onExportBackup = { "" },
            onImportBackup = { _, _ -> false }
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactsScreenEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactsScreenWithContactsScreenshot() {
    VauchiTheme(dynamicColor = false) {
        // Note: Due to lazy loading, we use the empty state for now
        // Real contacts would require more complex setup
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ExchangeScreenScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ExchangeScreen(
            onBack = {},
            onGenerateQr = { null },
            onScanQr = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun DeliveryStatusEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun DeliveryStatusWithRecordsScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun LabelsScreenEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun LabelsScreenWithLabelsScreenshot() {
    VauchiTheme(dynamicColor = false) {
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

// =============================================================
// Home Screen (ReadyScreen)
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun HomeScreenEmptyCardScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ReadyScreen(
            displayName = "Alice",
            publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
            card = MobileContactCard(
                displayName = "Alice",
                fields = emptyList()
            ),
            contactCount = 0U,
            onAddField = { _, _, _ -> },
            onRemoveField = {},
            onExchange = {},
            onContacts = {},
            onSettings = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun HomeScreenWithFieldsScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ReadyScreen(
            displayName = "Alice",
            publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
            card = MobileContactCard(
                displayName = "Alice",
                fields = listOf(
                    MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "alice@example.com",
                        fieldType = MobileFieldType.EMAIL
                    ),
                    MobileContactField(
                        id = "field-2",
                        label = "Phone",
                        value = "+41 79 123 45 67",
                        fieldType = MobileFieldType.PHONE
                    ),
                    MobileContactField(
                        id = "field-3",
                        label = "GitHub",
                        value = "alice",
                        fieldType = MobileFieldType.SOCIAL
                    )
                )
            ),
            contactCount = 5U,
            onAddField = { _, _, _ -> },
            onRemoveField = {},
            onExchange = {},
            onContacts = {},
            onSettings = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun HomeScreenSyncingScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ReadyScreen(
            displayName = "Alice",
            publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
            card = MobileContactCard(
                displayName = "Alice",
                fields = listOf(
                    MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "alice@example.com",
                        fieldType = MobileFieldType.EMAIL
                    )
                )
            ),
            contactCount = 3U,
            onAddField = { _, _, _ -> },
            onRemoveField = {},
            onExchange = {},
            onContacts = {},
            onSettings = {},
            syncState = SyncState.Syncing,
            isOnline = true
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun HomeScreenOfflineScreenshot() {
    VauchiTheme(dynamicColor = false) {
        ReadyScreen(
            displayName = "Alice",
            publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
            card = MobileContactCard(
                displayName = "Alice",
                fields = listOf(
                    MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "alice@example.com",
                        fieldType = MobileFieldType.EMAIL
                    )
                )
            ),
            contactCount = 3U,
            onAddField = { _, _, _ -> },
            onRemoveField = {},
            onExchange = {},
            onContacts = {},
            onSettings = {},
            syncState = SyncState.Idle,
            isOnline = false
        )
    }
}

// =============================================================
// Contact Detail Screen
// Note: ContactDetailScreen requires async data loading, so we test
// the individual components used within it
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactFieldItemScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                com.vauchi.ui.ContactFieldItem(
                    field = MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "bob@example.com",
                        fieldType = MobileFieldType.EMAIL
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                com.vauchi.ui.ContactFieldItem(
                    field = MobileContactField(
                        id = "field-2",
                        label = "Phone",
                        value = "+41 79 987 65 43",
                        fieldType = MobileFieldType.PHONE
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                com.vauchi.ui.ContactFieldItem(
                    field = MobileContactField(
                        id = "field-3",
                        label = "Twitter",
                        value = "@bobsmith",
                        fieldType = MobileFieldType.SOCIAL
                    )
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun VisibilityToggleItemScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                com.vauchi.ui.VisibilityToggleItem(
                    field = MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "alice@example.com",
                        fieldType = MobileFieldType.EMAIL
                    ),
                    isVisible = true,
                    onToggle = {}
                )
                Spacer(modifier = Modifier.height(12.dp))
                com.vauchi.ui.VisibilityToggleItem(
                    field = MobileContactField(
                        id = "field-2",
                        label = "Phone",
                        value = "+41 79 123 45 67",
                        fieldType = MobileFieldType.PHONE
                    ),
                    isVisible = false,
                    onToggle = {}
                )
            }
        }
    }
}

// =============================================================
// Recovery Screen Components
// Note: Full RecoveryScreen requires a ViewModel, so we test
// the RecoveryStep component
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun RecoveryStepComponentScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Recovery Steps",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                com.vauchi.ui.RecoveryStep(
                    number = 1,
                    title = "Create New Identity",
                    description = "First, create a new identity on your new device."
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.vauchi.ui.RecoveryStep(
                    number = 2,
                    title = "Generate Recovery Claim",
                    description = "Create a claim using your OLD public key from your lost identity."
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.vauchi.ui.RecoveryStep(
                    number = 3,
                    title = "Collect Vouchers",
                    description = "Meet with 3+ trusted contacts in person. Have them vouch for your recovery."
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.vauchi.ui.RecoveryStep(
                    number = 4,
                    title = "Share Recovery Proof",
                    description = "Once you have enough vouchers, share your recovery proof with all contacts."
                )
            }
        }
    }
}

// =============================================================
// Dark Mode Variants
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun WelcomeStepDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        WelcomeStep(onContinue = {}, onRestore = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun CreateIdentityStepFilledDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        CreateIdentityStep(
            displayName = "Alice",
            onDisplayNameChange = {},
            onContinue = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun AddFieldsStepDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        AddFieldsStep(
            phone = "+41 79 123 45 67",
            email = "alice@example.com",
            onPhoneChange = {},
            onEmailChange = {},
            onContinue = {},
            onBack = {},
            onSkip = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewStepDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun SecurityStepDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        SecurityStep(onContinue = {}, onBack = {})
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ReadyStepDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        ReadyStep()
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun SettingsScreenDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(
            displayName = "Alice",
            onBack = {},
            onExportBackup = { "" },
            onImportBackup = { _, _ -> false }
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactsScreenDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ExchangeScreenDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        ExchangeScreen(
            onBack = {},
            onGenerateQr = { null },
            onScanQr = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun DeliveryStatusWithRecordsDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
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
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun LabelsScreenWithLabelsDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
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

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun HomeScreenWithFieldsDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        ReadyScreen(
            displayName = "Alice",
            publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
            card = MobileContactCard(
                displayName = "Alice",
                fields = listOf(
                    MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "alice@example.com",
                        fieldType = MobileFieldType.EMAIL
                    ),
                    MobileContactField(
                        id = "field-2",
                        label = "Phone",
                        value = "+41 79 123 45 67",
                        fieldType = MobileFieldType.PHONE
                    )
                )
            ),
            contactCount = 5U,
            onAddField = { _, _, _ -> },
            onRemoveField = {},
            onExchange = {},
            onContacts = {},
            onSettings = {}
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactFieldItemDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                com.vauchi.ui.ContactFieldItem(
                    field = MobileContactField(
                        id = "field-1",
                        label = "Email",
                        value = "bob@example.com",
                        fieldType = MobileFieldType.EMAIL
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                com.vauchi.ui.ContactFieldItem(
                    field = MobileContactField(
                        id = "field-2",
                        label = "Phone",
                        value = "+41 79 987 65 43",
                        fieldType = MobileFieldType.PHONE
                    )
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun RecoveryStepComponentDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Recovery Steps",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                com.vauchi.ui.RecoveryStep(
                    number = 1,
                    title = "Create New Identity",
                    description = "First, create a new identity on your new device."
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.vauchi.ui.RecoveryStep(
                    number = 2,
                    title = "Generate Recovery Claim",
                    description = "Create a claim using your OLD public key."
                )
            }
        }
    }
}

// =============================================================
// German Locale Variants
// Note: Compose Preview Screenshot Testing uses preview configurations.
// For locale testing, we use @Preview with locale parameter.
// Since LocalizationManager loads strings at runtime from Context,
// we demonstrate the locale configuration approach here.
// =============================================================

/**
 * Helper composable that wraps content with a German locale configuration.
 * Note: This sets the system configuration for the preview context.
 */
@Composable
private fun GermanLocaleWrapper(content: @Composable () -> Unit) {
    val germanConfig = Configuration().apply {
        setLocale(Locale.GERMAN)
    }
    CompositionLocalProvider(LocalConfiguration provides germanConfig) {
        content()
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun WelcomeStepGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            WelcomeStep(onContinue = {}, onRestore = {})
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun CreateIdentityStepGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            CreateIdentityStep(
                displayName = "Anna",
                onDisplayNameChange = {},
                onContinue = {},
                onBack = {}
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun AddFieldsStepGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            AddFieldsStep(
                phone = "+49 170 123 4567",
                email = "anna@beispiel.de",
                onPhoneChange = {},
                onEmailChange = {},
                onContinue = {},
                onBack = {},
                onSkip = {}
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun PreviewStepGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            PreviewStep(
                data = OnboardingData(
                    displayName = "Anna",
                    phone = "+49 170 123 4567",
                    email = "anna@beispiel.de"
                ),
                onContinue = {},
                onBack = {}
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun SecurityStepGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            SecurityStep(onContinue = {}, onBack = {})
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun SettingsScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            SettingsScreen(
                displayName = "Anna",
                onBack = {},
                onExportBackup = { "" },
                onImportBackup = { _, _ -> false }
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun ContactsScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            ContactsScreen(
                onBack = {},
                onListContacts = { emptyList() },
                onRemoveContact = {},
                onContactClick = {},
                syncState = SyncState.Idle
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun ExchangeScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            ExchangeScreen(
                onBack = {},
                onGenerateQr = { null },
                onScanQr = {}
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun HomeScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            ReadyScreen(
                displayName = "Anna",
                publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
                card = MobileContactCard(
                    displayName = "Anna",
                    fields = listOf(
                        MobileContactField(
                            id = "field-1",
                            label = "E-Mail",
                            value = "anna@beispiel.de",
                            fieldType = MobileFieldType.EMAIL
                        ),
                        MobileContactField(
                            id = "field-2",
                            label = "Telefon",
                            value = "+49 170 123 4567",
                            fieldType = MobileFieldType.PHONE
                        )
                    )
                ),
                contactCount = 3U,
                onAddField = { _, _, _ -> },
                onRemoveField = {},
                onExchange = {},
                onContacts = {},
                onSettings = {}
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun LabelsScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            LabelsScreen(
                labels = listOf(
                    MobileVisibilityLabel(
                        id = "label-1",
                        name = "Arbeit",
                        contactCount = 5U,
                        visibleFieldCount = 3U,
                        createdAt = 1706745600UL,
                        modifiedAt = 1706745600UL
                    ),
                    MobileVisibilityLabel(
                        id = "label-2",
                        name = "Familie",
                        contactCount = 12U,
                        visibleFieldCount = 5U,
                        createdAt = 1706745500UL,
                        modifiedAt = 1706745700UL
                    )
                ),
                suggestedLabels = listOf("Arbeit", "Familie", "Freunde"),
                onBack = {},
                onLabelClick = {},
                onCreateLabel = {},
                onDeleteLabel = {},
                onRefresh = {}
            )
        }
    }
}

// =============================================================
// German Locale + Dark Mode Variants
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun WelcomeStepGermanDarkScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(darkTheme = true, dynamicColor = false) {
            WelcomeStep(onContinue = {}, onRestore = {})
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun SettingsScreenGermanDarkScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(darkTheme = true, dynamicColor = false) {
            SettingsScreen(
                displayName = "Anna",
                onBack = {},
                onExportBackup = { "" },
                onImportBackup = { _, _ -> false }
            )
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun HomeScreenGermanDarkScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(darkTheme = true, dynamicColor = false) {
            ReadyScreen(
                displayName = "Anna",
                publicId = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
                card = MobileContactCard(
                    displayName = "Anna",
                    fields = listOf(
                        MobileContactField(
                            id = "field-1",
                            label = "E-Mail",
                            value = "anna@beispiel.de",
                            fieldType = MobileFieldType.EMAIL
                        )
                    )
                ),
                contactCount = 3U,
                onAddField = { _, _, _ -> },
                onRemoveField = {},
                onExchange = {},
                onContacts = {},
                onSettings = {}
            )
        }
    }
}
