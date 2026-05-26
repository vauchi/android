// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreScreenView

/**
 * Contact detail screen rendered via core's `ContactDetailEngine`.
 *
 * Pure Humble UI shell (ADR-021/043) — delegates rendering and state to
 * [CoreScreenView]. The bespoke 1 045-LOC Compose layout was retired as
 * part of the Pure Humble UI retirement work
 * (_private/docs/problems/2026-04-28-pure-humble-ui-retire-native-screens/).
 *
 * `ContactDetailEngine` (extended in the Pair 3 core MR) emits the full screen:
 * Avatar + InfoPanel (Initials, Trust, Verified, Recovery Trusted,
 * Fingerprint, Exchange status), per-field FieldList + EditableText
 * notes, EditableText personal note, SettingsGroup (trust_permissions +
 * recovery_permissions), DeliverySummary InfoPanel, and InlineConfirm
 * for delete. Verify Fingerprint action is gated via
 * `verify_button_visible(is_verified, trust_level_enum)`.
 *
 * Core resolves the navigation (route_result emits NavigateTo with the
 * contact_id-parameterised AppScreen variant); the frontend renders it.
 */
@Composable
fun ContactDetailScreen(viewModel: CoreAppViewModel) {
    CoreScreenView(
        viewModel = viewModel,
        screenName = "ContactDetail",
        modifier = Modifier.fillMaxSize(),
    )
}
