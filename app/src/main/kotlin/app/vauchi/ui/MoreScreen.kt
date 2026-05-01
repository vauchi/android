// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.CoreAppViewModel

/**
 * "More" tab screen — secondary navigation surface for screens that
 * don't fit the bottom-tab set.
 *
 * Six of the seven entries dispatch directly through core
 * (`coreAppViewModel.navigateTo(...)`); the seventh (`Recovery`) is
 * still routed through the local `Screen` enum because it has a
 * native `RecoveryScreen` shell with iOS-style two-tab chrome.
 * `onRecovery` is the only remaining callback parameter — kept as a
 * thunk so `MainActivity` can mutate `currentScreen` without
 * `MoreScreen` having to know about the local enum.
 *
 * History: pre-2026-04-30 this composable took 7 separate
 * `() -> Unit` callbacks. Phase 1 + 1.1 of the Activity-enum-collapse
 * (vauchi/android!352, !353) collapsed 6 of those cases into the
 * core-driven dispatch path; this commit inlines those navigation
 * calls directly into the menu items so the parent doesn't need to
 * keep paraphrasing them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    coreAppViewModel: CoreAppViewModel,
    onRecovery: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("More") })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("more_screen"),
        ) {
            item {
                MoreMenuItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    testTag = "more_settings",
                    onClick = { coreAppViewModel.navigateTo("Settings") },
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    label = "Help",
                    testTag = "more_help",
                    onClick = { coreAppViewModel.navigateTo("Help") },
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.Devices,
                    label = "Linked Devices",
                    testTag = "more_devices",
                    onClick = { coreAppViewModel.navigateTo("DeviceManagement") },
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.PhoneAndroid,
                    label = "Replace Device",
                    testTag = "more_device_replacement",
                    onClick = { coreAppViewModel.navigateTo("DeviceReplacement") },
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.Lock,
                    label = "Backup & Recovery",
                    testTag = "more_recovery",
                    onClick = onRecovery,
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.Archive,
                    label = "Archived Contacts",
                    testTag = "more_archived_contacts",
                    onClick = { coreAppViewModel.navigateTo("ArchivedContacts") },
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.AutoMirrored.Filled.MergeType,
                    label = "Merge Contacts",
                    testTag = "more_merge_contacts",
                    onClick = { coreAppViewModel.navigateTo("ContactDuplicates") },
                )
            }
        }
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag(testTag),
    )
    HorizontalDivider()
}
