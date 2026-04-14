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

/**
 * "More" tab screen — a simple list of secondary navigation items
 * that were previously in the bottom bar (Settings, Help) plus
 * items that benefit from top-level discoverability (Linked Devices,
 * Backup & Recovery).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onDevices: () -> Unit,
    onRecovery: () -> Unit,
    onArchivedContacts: () -> Unit = {},
    onMergeContacts: () -> Unit = {},
    onDeviceReplacement: () -> Unit = {},
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
                    onClick = onSettings,
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    label = "Help",
                    testTag = "more_help",
                    onClick = onHelp,
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.Devices,
                    label = "Linked Devices",
                    testTag = "more_devices",
                    onClick = onDevices,
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Default.PhoneAndroid,
                    label = "Replace Device",
                    testTag = "more_device_replacement",
                    onClick = onDeviceReplacement,
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
                    onClick = onArchivedContacts,
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.AutoMirrored.Filled.MergeType,
                    label = "Merge Contacts",
                    testTag = "more_merge_contacts",
                    onClick = onMergeContacts,
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
