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
 * Help screen rendered via core's HelpWorkflowEngine.
 *
 * Follows the Humble Object pattern (ADR-021): delegates all rendering and
 * state management to [CoreScreenView], which renders the ScreenModel produced
 * by core. No direct FFI calls, no local state for search/categories.
 */
@Composable
fun HelpScreen(viewModel: CoreAppViewModel) {
    CoreScreenView(
        viewModel = viewModel,
        screenName = "Help",
        modifier = Modifier.fillMaxSize(),
    )
}
