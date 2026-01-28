// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uniffi.vauchi_mobile.MobileContact
import uniffi.vauchi_mobile.MobileContactField
import uniffi.vauchi_mobile.MobileVisibilityLabelDetail

/**
 * Detail screen for editing a visibility label.
 * Based on: features/visibility_labels.feature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelDetailScreen(
    labelId: String,
    onBack: () -> Unit,
    onGetLabel: (String) -> MobileVisibilityLabelDetail?,
    onRenameLabel: (String, String) -> Unit,
    onDeleteLabel: (String) -> Unit,
    onSetFieldVisibility: (String, String, Boolean) -> Unit,
    ownCardFields: List<MobileContactField>,
    contacts: List<MobileContact>
) {
    var labelDetail by remember { mutableStateOf<MobileVisibilityLabelDetail?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(labelId) {
        isLoading = true
        labelDetail = onGetLabel(labelId)
        isLoading = false
    }

    val detail = labelDetail

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "Label") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (detail != null) {
                        IconButton(onClick = {
                            newName = detail.name
                            showRenameDialog = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            detail == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Label not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Label info section
                    item {
                        Text(
                            text = "Label Info",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Name")
                                    Text(detail.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Contacts")
                                    Text("${detail.contactIds.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Visible Fields")
                                    Text("${detail.visibleFieldIds.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Field visibility section
                    if (ownCardFields.isNotEmpty()) {
                        item {
                            Text(
                                text = "Field Visibility",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        items(ownCardFields, key = { it.id }) { field ->
                            val isVisible = detail.visibleFieldIds.contains(field.id)
                            FieldVisibilityToggle(
                                field = field,
                                isVisible = isVisible,
                                onToggle = { visible ->
                                    onSetFieldVisibility(labelId, field.id, visible)
                                    // Update local state
                                    labelDetail = labelDetail?.copy(
                                        visibleFieldIds = if (visible) {
                                            detail.visibleFieldIds + field.id
                                        } else {
                                            detail.visibleFieldIds.filter { it != field.id }
                                        }
                                    )
                                }
                            )
                        }

                        item {
                            Text(
                                text = "Toggle which of your fields contacts in this label can see.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Contacts section
                    item {
                        Text(
                            text = "Contacts (${detail.contactIds.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (detail.contactIds.isEmpty()) {
                        item {
                            Text(
                                text = "No contacts in this label",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    } else {
                        items(detail.contactIds) { contactId ->
                            val contact = contacts.find { it.id == contactId }
                            ContactChip(
                                displayName = contact?.displayName ?: contactId,
                                isVerified = contact?.isVerified ?: false
                            )
                        }
                    }

                    item {
                        Text(
                            text = "To add or remove contacts, go to the contact's detail page and manage their labels.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Delete button
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Label")
                        }
                        Text(
                            text = "Deleting this label will not remove the contacts from your contacts list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // Rename dialog
    if (showRenameDialog && detail != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                newName = ""
            },
            title = { Text("Rename Label") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Label name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newName.trim() != detail.name) {
                            onRenameLabel(labelId, newName.trim())
                            labelDetail = labelDetail?.copy(name = newName.trim())
                            newName = ""
                            showRenameDialog = false
                        }
                    },
                    enabled = newName.isNotBlank() && newName.trim() != detail.name
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    newName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && detail != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Label?") },
            text = {
                Text("Are you sure you want to delete \"${detail.name}\"? Contacts will remain in your contacts list but will lose this label's visibility settings.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLabel(labelId)
                        showDeleteDialog = false
                        onBack()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FieldVisibilityToggle(
    field: MobileContactField,
    isVisible: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Switch(
                checked = isVisible,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun ContactChip(
    displayName: String,
    isVerified: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (isVerified) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = "Verified",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Extension function to copy MobileVisibilityLabelDetail
private fun MobileVisibilityLabelDetail.copy(
    id: String = this.id,
    name: String = this.name,
    contactIds: List<String> = this.contactIds,
    visibleFieldIds: List<String> = this.visibleFieldIds,
    createdAt: ULong = this.createdAt,
    modifiedAt: ULong = this.modifiedAt
): MobileVisibilityLabelDetail = MobileVisibilityLabelDetail(
    id = id,
    name = name,
    contactIds = contactIds,
    visibleFieldIds = visibleFieldIds,
    createdAt = createdAt,
    modifiedAt = modifiedAt
)
