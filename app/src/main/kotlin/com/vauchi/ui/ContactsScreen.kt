// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vauchi.util.LocalizationManager
import uniffi.vauchi_mobile.MobileContact
import uniffi.vauchi_mobile.MobileDemoContact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onListContacts: suspend () -> List<MobileContact>,
    onRemoveContact: (String) -> Unit,
    onContactClick: (String) -> Unit,
    syncState: SyncState = SyncState.Idle,
    onSync: () -> Unit = {},
    demoContact: MobileDemoContact? = null,
    onDismissDemo: () -> Unit = {}
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var contacts by remember { mutableStateOf<List<MobileContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var retryTrigger by remember { mutableStateOf(0) }

    val isRefreshing = syncState is SyncState.Syncing

    LaunchedEffect(retryTrigger) {
        isLoading = true
        hasError = false
        try {
            contacts = onListContacts()
        } catch (e: Exception) {
            hasError = true
        }
        isLoading = false
    }

    // Refresh contacts list when sync completes successfully
    LaunchedEffect(syncState) {
        if (syncState is SyncState.Success) {
            contacts = onListContacts()
        }
    }

    // Filter contacts based on search query
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                contact.card.fields.any { field ->
                    field.value.contains(searchQuery, ignoreCase = true) ||
                    field.label.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("contacts.title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onSync,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Search bar
            if (!isLoading && contacts.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    placeholder = { Text(localizationManager.t("contacts.search")) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (hasError) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = localizationManager.t("error.generic"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = localizationManager.t("action.retry"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { retryTrigger++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localizationManager.t("action.retry"))
                        }
                    }
                }
            } else if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .semantics {
                            contentDescription = "No contacts yet. Exchange cards with someone to add contacts."
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Show demo contact if available
                    if (demoContact != null) {
                        DemoContactCard(
                            demoContact = demoContact,
                            onDismiss = onDismissDemo
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    Text(
                        text = localizationManager.t("contacts.empty"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exchange cards with someone to add contacts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = localizationManager.t("contacts.no_match"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredContacts) { contact ->
                        ContactCard(
                            contact = contact,
                            onClick = { onContactClick(contact.id) },
                            onRemove = { onRemoveContact(contact.id) }
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
fun ContactCard(
    contact: MobileContact,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    val verificationStatus = if (contact.isVerified) "verified" else "not verified"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${contact.displayName}, $verificationStatus. Double tap to view details."
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Remove ${contact.displayName} from contacts"
                    }
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null, // Handled by parent semantics
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "ID: ${contact.id.take(16)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (contact.card.fields.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                contact.card.fields.forEach { field ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "${field.label}:",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            text = field.value,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(localizationManager.t("action.delete")) },
            text = { Text("Are you sure you want to remove ${contact.displayName} from your contacts?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onRemove()
                    }
                ) {
                    Text(localizationManager.t("action.delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(localizationManager.t("action.cancel"))
                }
            }
        )
    }
}

/**
 * Demo Contact Card component
 * Based on: features/demo_contact.feature
 *
 * Shows a demo contact card for users with no real contacts,
 * demonstrating how Vauchi updates work.
 */
@Composable
fun DemoContactCard(
    demoContact: MobileDemoContact,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Demo contact: ${demoContact.displayName}. This shows how Vauchi updates work."
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Demo icon
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "\uD83D\uDCA1", // lightbulb emoji
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = demoContact.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Demo badge
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiary
                            ) {
                                Text(
                                    text = "Demo",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary
                                )
                            }
                        }
                        Text(
                            text = demoContact.tipCategory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                // Dismiss button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics {
                        contentDescription = "Dismiss demo contact"
                    }
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tip content
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = demoContact.tipTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = demoContact.tipContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info text
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning, // Using warning as info icon placeholder
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "This is a demo contact showing how Vauchi updates work",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}
