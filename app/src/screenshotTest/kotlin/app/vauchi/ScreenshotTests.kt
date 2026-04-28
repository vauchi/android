// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vauchi.ui.LabelsScreen
import app.vauchi.ui.SyncState
import app.vauchi.ui.theme.VauchiTheme
import com.android.tools.screenshot.PreviewTest
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContactField
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileVisibilityLabel
import java.util.Locale

// VRT device spec: 360dp wide, 800dp tall, xhdpi (2x) = 720×1600 px.
// Large enough to catch layout issues, small enough to keep baselines under 60 KB each.
// Using xhdpi (320dpi/2x) instead of default xxhdpi (480dpi/3x) reduces file size by ~55%.
private const val VRT_DEVICE = "spec:width=360dp,height=800dp,dpi=320"

// =============================================================
// Main Screens - Light Theme
// =============================================================

// ContactsScreen* preview tests removed in Phase 1B.2: `Screen.Contacts`
// now renders through `CoreScreenView("Contacts")` against core's
// `ContactListEngine`, which is exercised from `core/vauchi-app/src/
// ui/contact_list.rs` unit tests. A preview-time screenshot would
// need a real `PlatformAppEngine` seeded with contacts, which is not
// how the preview runtime is set up.

// DeliveryStatus* preview tests removed in the 2026-04-28 Pure Humble
// UI retirement: `DeliveryStatusScreen` now renders through
// `CoreScreenView("DeliveryStatus")` against core's
// `DeliveryStatusEngine`, exercised from the engine's reachability test
// + golden fixtures. Same rationale as the ContactsScreen* removals
// above — preview-time screenshots would need a real `PlatformAppEngine`
// seeded with delivery records, which is not how the preview runtime
// is set up.

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
            onRefresh = {},
        )
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun LabelsScreenWithLabelsScreenshot() {
    VauchiTheme(dynamicColor = false) {
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
                    MobileVisibilityLabel(
                        id = "label-3",
                        name = "Friends",
                        contactCount = 8U,
                        visibleFieldCount = 4U,
                        createdAt = 1706745400UL,
                        modifiedAt = 1706745800UL,
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
                app.vauchi.ui.ContactFieldItem(
                    field =
                        MobileContactField(
                            id = "field-1",
                            label = "Email",
                            value = "bob@example.com",
                            fieldType = MobileFieldType.EMAIL,
                            note = null,
                        ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                app.vauchi.ui.ContactFieldItem(
                    field =
                        MobileContactField(
                            id = "field-2",
                            label = "Phone",
                            value = "+41 79 987 65 43",
                            fieldType = MobileFieldType.PHONE,
                            note = null,
                        ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                app.vauchi.ui.ContactFieldItem(
                    field =
                        MobileContactField(
                            id = "field-3",
                            label = "Twitter",
                            value = "@bobsmith",
                            fieldType = MobileFieldType.SOCIAL,
                            note = null,
                        ),
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
                app.vauchi.ui.VisibilityToggleItem(
                    field =
                        MobileContactField(
                            id = "field-1",
                            label = "Email",
                            value = "alice@example.com",
                            fieldType = MobileFieldType.EMAIL,
                            note = null,
                        ),
                    isVisible = true,
                    onToggle = {},
                )
                Spacer(modifier = Modifier.height(12.dp))
                app.vauchi.ui.VisibilityToggleItem(
                    field =
                        MobileContactField(
                            id = "field-2",
                            label = "Phone",
                            value = "+41 79 123 45 67",
                            fieldType = MobileFieldType.PHONE,
                            note = null,
                        ),
                    isVisible = false,
                    onToggle = {},
                )
            }
        }
    }
}

// =============================================================
// Dark Mode Variants
// =============================================================

// `ContactsScreenDarkScreenshot` removed in Phase 1B.2: same rationale
// as the light-theme previews above — the native `ContactsScreen` is
// gone; `CoreScreenView("Contacts")` covers the dark theme via the
// shared `VauchiTheme`.

// DeliveryStatusWithRecordsDarkScreenshot removed — see comment above.

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun LabelsScreenWithLabelsDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
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

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ContactFieldItemDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                app.vauchi.ui.ContactFieldItem(
                    field =
                        MobileContactField(
                            id = "field-1",
                            label = "Email",
                            value = "bob@example.com",
                            fieldType = MobileFieldType.EMAIL,
                            note = null,
                        ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                app.vauchi.ui.ContactFieldItem(
                    field =
                        MobileContactField(
                            id = "field-2",
                            label = "Phone",
                            value = "+41 79 987 65 43",
                            fieldType = MobileFieldType.PHONE,
                            note = null,
                        ),
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
    val germanConfig =
        Configuration().apply {
            setLocale(Locale.GERMAN)
        }
    CompositionLocalProvider(LocalConfiguration provides germanConfig) {
        content()
    }
}

// `ContactsScreenGermanScreenshot` removed in Phase 1B.2: same
// rationale as the light-theme previews above — the native
// `ContactsScreen` is gone; `CoreScreenView("Contacts")` renders in
// production and core owns the localised empty state.

@PreviewTest
@Preview(showSystemUi = true, locale = "de", device = VRT_DEVICE)
@Composable
fun LabelsScreenGermanScreenshot() {
    GermanLocaleWrapper {
        VauchiTheme(dynamicColor = false) {
            LabelsScreen(
                labels =
                    listOf(
                        MobileVisibilityLabel(
                            id = "label-1",
                            name = "Arbeit",
                            contactCount = 5U,
                            visibleFieldCount = 3U,
                            createdAt = 1706745600UL,
                            modifiedAt = 1706745600UL,
                        ),
                        MobileVisibilityLabel(
                            id = "label-2",
                            name = "Familie",
                            contactCount = 12U,
                            visibleFieldCount = 5U,
                            createdAt = 1706745500UL,
                            modifiedAt = 1706745700UL,
                        ),
                    ),
                suggestedLabels = listOf("Arbeit", "Familie", "Freunde"),
                onBack = {},
                onLabelClick = {},
                onCreateLabel = {},
                onDeleteLabel = {},
                onRefresh = {},
            )
        }
    }
}

// =============================================================
// German Locale + Dark Mode Variants
// =============================================================
