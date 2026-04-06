// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.util.ContactActions
import app.vauchi.util.LocalizationManager
import kotlinx.coroutines.launch
import uniffi.vauchi_platform.MobileContact
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContactField
import uniffi.vauchi_platform.MobileContactTrustLevel
import uniffi.vauchi_platform.MobileFieldNote
import uniffi.vauchi_platform.MobileReciprocity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contactId: String,
    onBack: () -> Unit,
    onGetContact: suspend (String) -> MobileContact?,
    onGetOwnCard: suspend () -> MobileContactCard?,
    onSetFieldVisibility: (String, String, Boolean) -> Unit,
    onIsFieldVisible: suspend (String, String) -> Boolean,
    onVerifyContact: suspend (String) -> Boolean,
    onGetOwnPublicKey: suspend () -> String?,
    onGetOwnFingerprint: (suspend () -> String?)? = null,
    onTrustForRecovery: (suspend (String) -> Boolean)? = null,
    onUntrustForRecovery: (suspend (String) -> Boolean)? = null,
    onGetContactNote: (suspend (String) -> String?)? = null,
    onSetContactNote: (suspend (String, String) -> Unit)? = null,
    onGetContactFieldNotes: (suspend (String) -> List<MobileFieldNote>)? = null,
    onSetContactFieldNote: (suspend (String, String, String) -> Unit)? = null,
    onDeleteContactFieldNote: (suspend (String, String) -> Unit)? = null,
    onSetProposalTrusted: (suspend (String, Boolean) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var contact by remember { mutableStateOf<MobileContact?>(null) }
    var ownCard by remember { mutableStateOf<MobileContactCard?>(null) }
    var ownPublicKey by remember { mutableStateOf<String?>(null) }
    var ownFingerprint by remember { mutableStateOf<String?>(null) }
    var fieldVisibility by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showVerification by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var isTogglingTrust by remember { mutableStateOf(false) }
    var personalNote by remember { mutableStateOf("") }
    var isEditingNote by remember { mutableStateOf(false) }
    var noteEditText by remember { mutableStateOf("") }
    var fieldNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var proposalTrusted by remember { mutableStateOf(false) }
    var isTogglingProposalTrust by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(contactId) {
        contact = onGetContact(contactId)
        ownCard = onGetOwnCard()
        ownPublicKey = onGetOwnPublicKey()
        ownFingerprint = onGetOwnFingerprint?.invoke()

        // Load visibility for each of our fields
        ownCard?.let { card ->
            val visibilityMap = mutableMapOf<String, Boolean>()
            card.fields.forEach { field ->
                visibilityMap[field.label] = onIsFieldVisible(contactId, field.label)
            }
            fieldVisibility = visibilityMap
        }

        // Load notes and proposal trust
        personalNote = onGetContactNote?.invoke(contactId) ?: ""
        val notes = onGetContactFieldNotes?.invoke(contactId) ?: emptyList()
        fieldNotes = notes.associate { it.fieldId to it.note }
        proposalTrusted = contact?.proposalTrusted ?: false

        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact?.displayName ?: "Contact") },
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
        } else if (contact == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(localizationManager.t("contacts.not_found"))
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                // Contact Info Section
                item {
                    Text(
                        text = "Their Info",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                }

                contact?.let { c ->
                    if (c.card.fields.isEmpty()) {
                        item {
                            Text(
                                text = "No contact info shared",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(c.card.fields) { field ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ContactFieldItem(field = field)
                                ContactFieldNoteItem(
                                    note = fieldNotes[field.id] ?: "",
                                    onSave = { newNote ->
                                        scope.launch {
                                            if (newNote.isEmpty()) {
                                                onDeleteContactFieldNote?.invoke(contactId, field.id)
                                            } else {
                                                onSetContactFieldNote?.invoke(contactId, field.id, newNote)
                                            }
                                            fieldNotes = fieldNotes + (field.id to newNote)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // Personal note
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        PersonalNoteCard(
                            note = personalNote,
                            isEditing = isEditingNote,
                            editText = noteEditText,
                            onEditTextChange = { noteEditText = it },
                            onStartEdit = {
                                noteEditText = personalNote
                                isEditingNote = true
                            },
                            onSave = {
                                scope.launch {
                                    onSetContactNote?.invoke(contactId, noteEditText)
                                    personalNote = noteEditText
                                    isEditingNote = false
                                }
                            },
                            onCancel = { isEditingNote = false },
                        )
                    }

                    // Trust level (derived from core — ADR-021/034)
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        TrustLevelCard(
                            trustLevel = c.trustLevel,
                            onVerify =
                                if (c.trustLevel == MobileContactTrustLevel.STANDARD ||
                                    c.trustLevel == MobileContactTrustLevel.HIGH
                                ) {
                                    { showVerification = true }
                                } else {
                                    null
                                },
                            localizationManager = localizationManager,
                        )
                    }

                    // Exchange status (reciprocity)
                    if (c.reciprocity == MobileReciprocity.PENDING ||
                        c.reciprocity == MobileReciprocity.UNRECIPROCATED
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            ExchangeStatusCard(reciprocity = c.reciprocity)
                        }
                    }

                    // Recovery trust status
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (c.isRecoveryTrusted) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                ),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (c.isRecoveryTrusted) "Recovery Trusted" else "Not Recovery Trusted",
                                        style = MaterialTheme.typography.titleSmall,
                                        color =
                                            if (c.isRecoveryTrusted) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Text(
                                        text =
                                            if (c.isRecoveryTrusted) {
                                                "This contact can vouch for your identity recovery"
                                            } else {
                                                "Trust this contact to help recover your identity"
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (c.isRecoveryTrusted) {
                                                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            },
                                    )
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTogglingTrust = true
                                            val success =
                                                if (c.isRecoveryTrusted) {
                                                    onUntrustForRecovery?.invoke(contactId) ?: false
                                                } else {
                                                    onTrustForRecovery?.invoke(contactId) ?: false
                                                }
                                            if (success) {
                                                contact = onGetContact(contactId)
                                            }
                                            isTogglingTrust = false
                                        }
                                    },
                                    enabled = !isTogglingTrust,
                                    colors =
                                        if (c.isRecoveryTrusted) {
                                            ButtonDefaults.outlinedButtonColors()
                                        } else {
                                            ButtonDefaults.buttonColors()
                                        },
                                ) {
                                    Text(if (c.isRecoveryTrusted) "Remove" else "Trust")
                                }
                            }
                        }
                    }
                }

                // Proposal trust
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (proposalTrusted) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                            ),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (proposalTrusted) "Proposal Trusted" else "Not Proposal Trusted",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text =
                                        if (proposalTrusted) {
                                            "This contact can propose new contacts to you"
                                        } else {
                                            "Allow this contact to propose new contacts"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isTogglingProposalTrust = true
                                        val newValue = !proposalTrusted
                                        val success = onSetProposalTrusted?.invoke(contactId, newValue) ?: false
                                        if (success) {
                                            proposalTrusted = newValue
                                        }
                                        isTogglingProposalTrust = false
                                    }
                                },
                                enabled = !isTogglingProposalTrust,
                                colors =
                                    if (proposalTrusted) {
                                        ButtonDefaults.outlinedButtonColors()
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                            ) {
                                Text(if (proposalTrusted) "Remove" else "Trust")
                            }
                        }
                    }
                }

                // Divider
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Visibility Section
                item {
                    Text(
                        text = "What They Can See",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Toggle which of your fields this contact can see",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ownCard?.let { card ->
                    if (card.fields.isEmpty()) {
                        item {
                            Text(
                                text = "You have no fields to share",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(card.fields) { field ->
                            VisibilityToggleItem(
                                field = field,
                                isVisible = fieldVisibility[field.label] ?: true,
                                onToggle = { visible ->
                                    fieldVisibility = fieldVisibility + (field.label to visible)
                                    onSetFieldVisibility(contactId, field.label, visible)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Verification Dialog
    if (showVerification) {
        AlertDialog(
            onDismissRequest = { if (!isVerifying) showVerification = false },
            title = { Text("Verify ${contact?.displayName}") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Compare these fingerprints with ${contact?.displayName} in person to verify their identity.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // Their fingerprint
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Their Fingerprint",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = contact?.fingerprint ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                        }
                    }

                    // Our fingerprint
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Your Fingerprint",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ownFingerprint ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                        }
                    }

                    Text(
                        text = "Only mark as verified if the fingerprints match!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isVerifying = true
                            val success = onVerifyContact(contactId)
                            if (success) {
                                contact = onGetContact(contactId)
                                showVerification = false
                            }
                            isVerifying = false
                        }
                    },
                    enabled = !isVerifying,
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Mark as Verified")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showVerification = false },
                    enabled = !isVerifying,
                ) {
                    Text(localizationManager.t("action.cancel"))
                }
            },
        )
    }
}

@Composable
fun ContactFieldItem(field: MobileContactField) {
    val context = LocalContext.current

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${field.label}: ${field.value}. Tap to ${ContactActions.getActionDescription(field.fieldType).lowercase()}"
                }.clickable { ContactActions.openField(context, field) },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = ContactActions.getActionDescription(field.fieldType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun VisibilityToggleItem(
    field: MobileContactField,
    isVisible: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val visibilityState = if (isVisible) "visible" else "hidden"
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "${field.label}: ${field.value}, currently $visibilityState"
                },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isVisible) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (isVisible) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (isVisible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                )
            }
            Switch(
                checked = isVisible,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
fun TrustLevelCard(
    trustLevel: MobileContactTrustLevel,
    onVerify: (() -> Unit)?,
    localizationManager: LocalizationManager,
) {
    val (containerColor, contentColor) =
        when (trustLevel) {
            MobileContactTrustLevel.CAUTIOUS -> {
                MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
            }

            MobileContactTrustLevel.STANDARD -> {
                MaterialTheme.colorScheme.surfaceVariant to
                    MaterialTheme.colorScheme.onSurfaceVariant
            }

            MobileContactTrustLevel.HIGH -> {
                MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
            }

            MobileContactTrustLevel.VERIFIED -> {
                MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
            }
        }

    val title =
        when (trustLevel) {
            MobileContactTrustLevel.CAUTIOUS -> "Needs Re-verification"
            MobileContactTrustLevel.STANDARD -> localizationManager.t("contacts.not_verified")
            MobileContactTrustLevel.HIGH -> "High Trust"
            MobileContactTrustLevel.VERIFIED -> localizationManager.t("contacts.verified")
        }

    val subtitle =
        when (trustLevel) {
            MobileContactTrustLevel.CAUTIOUS -> {
                "This contact's identity was recovered — re-verify in person"
            }

            MobileContactTrustLevel.STANDARD -> {
                "Verify fingerprints in person"
            }

            MobileContactTrustLevel.HIGH -> {
                "Exchanged via close-range transport"
            }

            MobileContactTrustLevel.VERIFIED -> {
                "You have verified this contact's identity"
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
            if (onVerify != null) {
                Button(onClick = onVerify) {
                    Text("Verify")
                }
            }
        }
    }
}

@Composable
fun ExchangeStatusCard(reciprocity: MobileReciprocity) {
    val title: String
    val subtitle: String
    val containerColor: androidx.compose.ui.graphics.Color
    val contentColor: androidx.compose.ui.graphics.Color

    when (reciprocity) {
        MobileReciprocity.PENDING -> {
            title = "Awaiting confirmation"
            subtitle = "Verifying that both sides completed the exchange"
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }

        MobileReciprocity.UNRECIPROCATED -> {
            title = "May not have your card"
            subtitle = "The other party may not have completed the exchange"
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }

        else -> {
            return
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
fun PersonalNoteCard(
    note: String,
    isEditing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Private Note",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = onEditTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a private note...") },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    TextButton(onClick = onSave) {
                        Text("Save")
                    }
                }
            } else {
                Text(
                    text = note.ifEmpty { "Add a private note..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (note.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onStartEdit() },
                    maxLines = 3,
                )
            }
            Text(
                text = "Only visible to you — never shared with this contact.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
fun ContactFieldNoteItem(
    note: String,
    onSave: (String) -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(note) }

    if (isEditing) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Private note...") },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
            TextButton(onClick = {
                onSave(editText)
                isEditing = false
            }) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { isEditing = false }) {
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
        }
    } else if (note.isNotEmpty()) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .clickable {
                        editText = note
                        isEditing = true
                    },
            maxLines = 2,
        )
    } else {
        Text(
            text = "Add note",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .clickable {
                        editText = ""
                        isEditing = true
                    },
        )
    }
}
