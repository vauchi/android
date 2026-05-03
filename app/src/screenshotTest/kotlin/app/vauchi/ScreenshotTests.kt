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
import app.vauchi.ui.SyncState
import app.vauchi.ui.theme.VauchiTheme
import com.android.tools.screenshot.PreviewTest
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContactField
import uniffi.vauchi_platform.MobileFieldType
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
// UI retirement. The Android-side `DeliveryStatusScreen.kt` wrapper
// itself was retired in `vauchi/android!371` (Phase 4a residual of
// `2026-05-01-android-humble-ui-deep-retirement`) — it had zero
// callers, and `coreScreenIdToVariant` never mapped `delivery_status`
// so no core route reached it. Delivery-status behavior is exercised
// in `core/vauchi-app/tests/reachability/delivery_status.rs` against
// `DeliveryStatusEngine` until/unless a frontend route revives the
// screen.

// LabelsScreen* preview tests removed in the 2026-04-28 Pure Humble UI
// retirement (Pair 2): `Screen.Labels` already renders through
// `CoreScreenView("Groups")` against core's `GroupsEngine` /
// `GroupDetailEngine`. Same rationale as the ContactsScreen* and
// DeliveryStatusScreen* removals above — preview-time screenshots would
// need a real `PlatformAppEngine` seeded with labels, which is not how
// the preview runtime is set up. Behavioral coverage now lives in
// core's group engines + reachability tests.

// =============================================================
// Contact Detail Screen
// Note: ContactDetailScreen requires async data loading, so we test
// the individual components used within it
// =============================================================

// ContactFieldItemScreenshot + VisibilityToggleItemScreenshot removed in
// the 2026-04-28 Pure Humble UI retirement: those were preview tests for
// helpers (ContactFieldItem, VisibilityToggleItem) that lived inside the
// now-deleted ContactDetailScreen. Field rendering is now driven by core
// via Component::FieldList. Same rationale as the
// DeliveryStatusEmptyScreenshot removal earlier in this file.

// =============================================================
// Dark Mode Variants
// =============================================================

// `ContactsScreenDarkScreenshot` removed in Phase 1B.2: same rationale
// as the light-theme previews above — the native `ContactsScreen` is
// gone; `CoreScreenView("Contacts")` covers the dark theme via the
// shared `VauchiTheme`.

// DeliveryStatusWithRecordsDarkScreenshot removed — see comment above.

// LabelsScreenWithLabelsDarkScreenshot removed — see LabelsScreen*
// comment in the light-theme section above.

// ContactFieldItemDarkScreenshot removed — see ContactFieldItemScreenshot
// comment above (helper deleted alongside ContactDetailScreen).

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

// LabelsScreenGermanScreenshot removed — see LabelsScreen* comment in
// the light-theme section above. Localized strings now live in core.

// =============================================================
// German Locale + Dark Mode Variants
// =============================================================
