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
 * Delivery status screen rendered via core's `DeliveryStatusEngine`.
 *
 * Pure Humble UI shell (ADR-021/043) — delegates rendering and state to
 * [CoreScreenView]. The bespoke 3-tab Compose layout (Recent / Failed /
 * Pending) was retired as part of the Pure Humble UI retirement work
 * (_private/docs/problems/2026-04-28-pure-humble-ui-retire-native-screens/).
 * Sections are now emitted by core as Text(section_*) headers + Divider
 * + StatusIndicator components.
 */
@Composable
fun DeliveryStatusScreen(viewModel: CoreAppViewModel) {
    CoreScreenView(
        viewModel = viewModel,
        screenName = "DeliveryStatus",
        modifier = Modifier.fillMaxSize(),
    )
}
