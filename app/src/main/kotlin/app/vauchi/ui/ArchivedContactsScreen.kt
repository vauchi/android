// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.util.LocalizationManager
import kotlinx.coroutines.launch
import uniffi.vauchi_platform.MobileContact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedContactsScreen(
    onBack: () -> Unit,
    onListArchivedContacts: suspend () -> List<MobileContact>,
    onUnarchiveContact: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var archivedContacts by remember { mutableStateOf<List<MobileContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        archivedContacts = onListArchivedContacts()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("contacts.archived_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (archivedContacts.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = localizationManager.t("contacts.archived_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(archivedContacts, key = { it.id }) { contact ->
                    ArchivedContactItem(
                        contact = contact,
                        localizationManager = localizationManager,
                        onUnarchive = {
                            scope.launch {
                                onUnarchiveContact(contact.id)
                                refreshTrigger++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedContactItem(
    contact: MobileContact,
    localizationManager: app.vauchi.util.LocalizationManager,
    onUnarchive: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                contact.displayName,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Archived contact: ${contact.displayName}"
                    },
            )
        },
        trailingContent = {
            TextButton(
                onClick = onUnarchive,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Unarchive ${contact.displayName}"
                    },
            ) {
                Icon(
                    Icons.Default.Unarchive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(localizationManager.t("contacts.action_unarchive"))
            }
        },
    )
    HorizontalDivider()
}
