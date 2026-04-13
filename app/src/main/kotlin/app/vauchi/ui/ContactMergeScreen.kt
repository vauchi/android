// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Close
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
import uniffi.vauchi_platform.MobileDuplicatePair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactMergeScreen(
    onBack: () -> Unit,
    onFindDuplicates: suspend () -> List<MobileDuplicatePair>,
    onGetContact: suspend (String) -> MobileContact?,
    onMergeContacts: suspend (String, String) -> MobileContact,
    onDismissDuplicate: suspend (String, String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var duplicates by remember { mutableStateOf<List<MobileDuplicatePair>>(emptyList()) }
    var contactCache by remember { mutableStateOf<Map<String, MobileContact>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var confirmingPair by remember { mutableStateOf<MobileDuplicatePair?>(null) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        val pairs = onFindDuplicates()
        val ids = pairs.flatMap { listOf(it.id1, it.id2) }.toSet()
        val cache = mutableMapOf<String, MobileContact>()
        for (id in ids) {
            onGetContact(id)?.let { cache[id] = it }
        }
        contactCache = cache
        duplicates = pairs
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("contacts.find_duplicates")) },
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
        } else if (duplicates.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.MergeType,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = localizationManager.t("contacts.no_duplicates"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(duplicates, key = { "${it.id1}-${it.id2}" }) { pair ->
                    DuplicatePairItem(
                        pair = pair,
                        contact1 = contactCache[pair.id1],
                        contact2 = contactCache[pair.id2],
                        localizationManager = localizationManager,
                        onMerge = { confirmingPair = pair },
                        onDismiss = {
                            scope.launch {
                                onDismissDuplicate(pair.id1, pair.id2)
                                refreshTrigger++
                            }
                        },
                    )
                }
            }
        }
    }

    // Merge confirmation dialog (irrevocable action per ADR-022)
    if (confirmingPair != null) {
        val pair = confirmingPair!!
        val name1 = contactCache[pair.id1]?.displayName ?: pair.id1
        val name2 = contactCache[pair.id2]?.displayName ?: pair.id2

        AlertDialog(
            onDismissRequest = { confirmingPair = null },
            title = { Text(localizationManager.t("contacts.merge_confirm")) },
            text = { Text(localizationManager.t("contacts.merge_title")) },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        val p = pair
                        confirmingPair = null
                        scope.launch {
                            try {
                                onMergeContacts(p.id1, p.id2)
                                onShowMessage(localizationManager.t("contacts.toast_merged"))
                                refreshTrigger++
                            } catch (e: Exception) {
                                onShowMessage("Merge failed: ${e.message}")
                            }
                        }
                    }) {
                        Text("$name1 (${localizationManager.t("contacts.merge_keep_primary")})")
                    }
                    TextButton(onClick = {
                        val p = pair
                        confirmingPair = null
                        scope.launch {
                            try {
                                onMergeContacts(p.id2, p.id1)
                                onShowMessage(localizationManager.t("contacts.toast_merged"))
                                refreshTrigger++
                            } catch (e: Exception) {
                                onShowMessage("Merge failed: ${e.message}")
                            }
                        }
                    }) {
                        Text("$name2 (${localizationManager.t("contacts.merge_keep_primary")})")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingPair = null }) {
                    Text(localizationManager.t("action.cancel"))
                }
            },
        )
    }
}

@Composable
private fun DuplicatePairItem(
    pair: MobileDuplicatePair,
    contact1: MobileContact?,
    contact2: MobileContact?,
    localizationManager: LocalizationManager,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name1 = contact1?.displayName ?: pair.id1
    val name2 = contact2?.displayName ?: pair.id2
    val percent = (pair.similarity * 100).toInt()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "$name1 and $name2, $percent percent match"
                },
    ) {
        // Similarity badge
        Text(
            text =
                localizationManager
                    .t("contacts.merge_similarity")
                    .replace("{percent}", percent.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        Spacer(Modifier.height(8.dp))

        // Contact pair
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(name1, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.MergeType,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(name2, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(8.dp))

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onMerge,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Merge $name1 and $name2"
                    },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MergeType,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(localizationManager.t("contacts.merge_confirm"))
            }
            TextButton(
                onClick = onDismiss,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Dismiss duplicate pair $name1 and $name2"
                    },
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(localizationManager.t("contacts.dismiss_duplicate"))
            }
        }
    }
    HorizontalDivider()
}
