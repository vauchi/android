// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

// Phase 1B.4 (core-gui-architecture-alignment): the Recovery screen is
// now a thin two-tab shell around core's `RecoveryEngine` ("Recover"
// tab, core!647) and `RecoveryHelpEngine` ("Help Others" tab, core!645).
// Both engines own their full state machines and action handlers —
// this shell only provides tab chrome and the Android system-back
// handler so the user can return to More.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreScreenView

@Composable
fun RecoveryScreen(
    coreAppViewModel: CoreAppViewModel,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Recover") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Help Others") },
            )
        }
        when (selectedTab) {
            0 -> {
                CoreScreenView(
                    viewModel = coreAppViewModel,
                    screenName = "Recovery",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            1 -> {
                CoreScreenView(
                    viewModel = coreAppViewModel,
                    screenName = "RecoveryHelp",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
