package com.vauchi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vauchi.util.ClipboardUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String,
    onBack: () -> Unit,
    onExportBackup: suspend (String) -> String?,
    onImportBackup: suspend (String, String) -> Boolean,
    onUpdateDisplayName: suspend (String) -> Boolean = { true },
    relayUrl: String = "",
    onRelayUrlChange: (String) -> Unit = {},
    syncState: SyncState = SyncState.Idle,
    onSync: () -> Unit = {},
    onDevices: () -> Unit = {},
    onRecovery: () -> Unit = {},
    onLabels: () -> Unit = {},
    onDeliveryStatus: () -> Unit = {},
    failedDeliveryCount: Int = 0,
    onCheckPasswordStrength: (String) -> PasswordStrengthResult = { PasswordStrengthResult() },
    // Demo contact
    showRestoreDemoOption: Boolean = false,
    onRestoreDemo: () -> Unit = {},
    // Aha moments (tips)
    ahaMomentsProgress: Pair<Int, Int> = Pair(0, 0),
    onResetAhaMoments: () -> Unit = {},
    // Accessibility settings
    reduceMotion: Boolean = false,
    onReduceMotionChange: (Boolean) -> Unit = {},
    highContrast: Boolean = false,
    onHighContrastChange: (Boolean) -> Unit = {},
    largeTouchTargets: Boolean = false,
    onLargeTouchTargetsChange: (Boolean) -> Unit = {},
    // Content Updates
    isContentUpdatesSupported: Boolean = false,
    onCheckContentUpdates: suspend () -> ContentUpdateStatus? = { null },
    onApplyContentUpdates: suspend () -> ContentApplyResult? = { null },
    // Certificate Pinning
    isCertificatePinningEnabled: Boolean = false,
    onSetPinnedCertificate: (String) -> Unit = {}
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var editableRelayUrl by remember(relayUrl) { mutableStateOf(relayUrl) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Account Section
            Text(
                text = "Account",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { showEditNameDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Display Name",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider()

            // Sync Section
            Text(
                text = "Sync",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = editableRelayUrl,
                        onValueChange = { editableRelayUrl = it },
                        label = { Text("Relay URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (editableRelayUrl != relayUrl) {
                        Button(
                            onClick = { onRelayUrlChange(editableRelayUrl) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Relay URL")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (syncState) {
                                is SyncState.Idle -> "Ready to sync"
                                is SyncState.Syncing -> "Syncing..."
                                is SyncState.Success -> "Sync complete"
                                is SyncState.Error -> "Sync failed"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (syncState) {
                                is SyncState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Button(
                            onClick = onSync,
                            enabled = syncState !is SyncState.Syncing
                        ) {
                            if (syncState is SyncState.Syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Sync Now")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Message Delivery Section
            Text(
                text = "Message Delivery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = onDeliveryStatus
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Delivery Status",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "View message delivery history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (failedDeliveryCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error
                            ) {
                                Text("$failedDeliveryCount failed")
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go",
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = 180f }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Backup Section
            Text(
                text = "Backup & Restore",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Back up your identity to restore it on another device or after reinstalling.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export Backup")
                }
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import Backup")
                }
            }

            HorizontalDivider()

            // Privacy Section
            Text(
                text = "Privacy",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedButton(
                onClick = onLabels,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Visibility Labels")
            }

            Text(
                text = "Organize contacts into groups and control what they can see",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Devices & Recovery Section
            Text(
                text = "Devices & Recovery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onDevices,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Devices")
                }
                OutlinedButton(
                    onClick = onRecovery,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Recovery")
                }
            }

            // Certificate Pinning Section
            CertificatePinningSection(
                isEnabled = isCertificatePinningEnabled,
                onSetCertificate = onSetPinnedCertificate
            )

            // Content Updates Section (only if supported)
            if (isContentUpdatesSupported) {
                HorizontalDivider()

                ContentUpdatesSection(
                    onCheckUpdates = onCheckContentUpdates,
                    onApplyUpdates = onApplyContentUpdates
                )
            }

            HorizontalDivider()

            // Accessibility Section
            Text(
                text = "Accessibility",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "These settings supplement system accessibility features.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AccessibilityToggle(
                        title = "Reduce Motion",
                        description = "Minimize animations and transitions",
                        checked = reduceMotion,
                        onCheckedChange = onReduceMotionChange
                    )

                    HorizontalDivider()

                    AccessibilityToggle(
                        title = "High Contrast",
                        description = "Increase color contrast for better visibility",
                        checked = highContrast,
                        onCheckedChange = onHighContrastChange
                    )

                    HorizontalDivider()

                    AccessibilityToggle(
                        title = "Large Touch Targets",
                        description = "Increase button and control sizes",
                        checked = largeTouchTargets,
                        onCheckedChange = onLargeTouchTargetsChange
                    )
                }
            }

            HorizontalDivider()

            // Help & Support Section
            Text(
                text = "Help & Support",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            val context = LocalContext.current
            val openUrl = { url: String ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }

            // Demo contact restore option
            if (showRestoreDemoOption) {
                HelpLinkItem(
                    title = "Show Demo Contact",
                    subtitle = "Learn how updates work",
                    onClick = onRestoreDemo
                )
            }

            // Reset tips (aha moments)
            HelpLinkItem(
                title = "Reset Tips",
                subtitle = "${ahaMomentsProgress.first}/${ahaMomentsProgress.second} seen",
                onClick = onResetAhaMoments
            )

            HelpLinkItem(
                title = "User Guide",
                subtitle = "Learn how to use Vauchi",
                onClick = { openUrl("https://vauchi.app/user-guide") }
            )

            HelpLinkItem(
                title = "FAQ",
                subtitle = "Frequently asked questions",
                onClick = { openUrl("https://vauchi.app/faq") }
            )

            HelpLinkItem(
                title = "Report Issue",
                subtitle = "Report bugs or request features",
                onClick = { openUrl("https://github.com/vauchi/issues") }
            )

            HelpLinkItem(
                title = "Privacy Policy",
                subtitle = "How we protect your data",
                onClick = { openUrl("https://vauchi.app/privacy") }
            )

            HorizontalDivider()

            // About Section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Vauchi",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Privacy-focused contact exchange",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val versionName = packageInfo.versionName ?: "1.0.0"
                    val versionCode = packageInfo.longVersionCode
                    Text(
                        text = "Version $versionName (build $versionCode)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showExportDialog) {
        ExportBackupDialog(
            onDismiss = { showExportDialog = false },
            onExport = onExportBackup,
            onResult = { success, message ->
                snackbarMessage = message
                if (success) showExportDialog = false
            },
            onCheckPasswordStrength = onCheckPasswordStrength
        )
    }

    if (showImportDialog) {
        ImportBackupDialog(
            onDismiss = { showImportDialog = false },
            onImport = onImportBackup,
            onResult = { success, message ->
                snackbarMessage = message
                if (success) showImportDialog = false
            }
        )
    }

    if (showEditNameDialog) {
        EditDisplayNameDialog(
            currentName = displayName,
            onDismiss = { showEditNameDialog = false },
            onUpdateName = onUpdateDisplayName,
            onResult = { success, message ->
                snackbarMessage = message
                if (success) showEditNameDialog = false
            }
        )
    }
}

@Composable
fun ExportBackupDialog(
    onDismiss: () -> Unit,
    onExport: suspend (String) -> String?,
    onResult: (Boolean, String) -> Unit,
    onCheckPasswordStrength: (String) -> PasswordStrengthResult = { PasswordStrengthResult() }
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var backupCode by remember { mutableStateOf<String?>(null) }
    var passwordStrength by remember { mutableStateOf(PasswordStrengthResult()) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Check password strength as user types
    LaunchedEffect(password) {
        if (password.isNotEmpty()) {
            passwordStrength = onCheckPasswordStrength(password)
        } else {
            passwordStrength = PasswordStrengthResult()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(if (backupCode == null) "Export Backup" else "Backup Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (backupCode == null) {
                    Text(
                        text = "Create a password to encrypt your backup. You'll need this password to restore.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    // Password strength indicator
                    if (password.isNotEmpty()) {
                        PasswordStrengthIndicator(strength = passwordStrength)
                    }

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                    if (password.isNotEmpty() && confirmPassword.isNotEmpty() && password != confirmPassword) {
                        Text(
                            text = "Passwords don't match",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(
                        text = "Your backup code has been copied to clipboard. Store it safely!",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = backupCode!!.take(50) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (backupCode == null) {
                TextButton(
                    onClick = {
                        if (passwordStrength.isAcceptable && password == confirmPassword) {
                            isLoading = true
                            coroutineScope.launch {
                                val result = onExport(password)
                                if (result != null) {
                                    // Copy to clipboard with auto-clear after 30 seconds
                                    ClipboardUtils.copyWithAutoClear(context, coroutineScope, result, "Vauchi Backup")
                                    backupCode = result
                                    onResult(false, "Backup copied to clipboard (auto-clears in 30s)")
                                } else {
                                    onResult(false, "Failed to create backup")
                                }
                                isLoading = false
                            }
                        }
                    },
                    enabled = passwordStrength.isAcceptable && password == confirmPassword && !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("Export")
                    }
                }
            } else {
                TextButton(onClick = { onResult(true, "Backup exported successfully") }) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            if (backupCode == null) {
                TextButton(onClick = onDismiss, enabled = !isLoading) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ImportBackupDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String, String) -> Boolean,
    onResult: (Boolean, String) -> Unit
) {
    var backupData by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Import Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Paste your backup code and enter the password you used when creating the backup.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = backupData,
                    onValueChange = { backupData = it },
                    label = { Text("Backup Code") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !isLoading
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (backupData.isNotBlank() && password.isNotBlank()) {
                        isLoading = true
                        coroutineScope.launch {
                            val success = onImport(backupData.trim(), password)
                            if (success) {
                                onResult(true, "Backup restored successfully")
                            } else {
                                onResult(false, "Failed to restore backup. Check your password.")
                            }
                            isLoading = false
                        }
                    }
                },
                enabled = backupData.isNotBlank() && password.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Import")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditDisplayNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onUpdateName: suspend (String) -> Boolean,
    onResult: (Boolean, String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Edit Display Name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Enter your new display name. This is how contacts will see you.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                if (newName.isBlank()) {
                    Text(
                        text = "Name cannot be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank() && newName != currentName) {
                        isLoading = true
                        coroutineScope.launch {
                            val success = onUpdateName(newName.trim())
                            if (success) {
                                onResult(true, "Display name updated")
                            } else {
                                onResult(false, "Failed to update display name")
                            }
                            isLoading = false
                        }
                    } else if (newName == currentName) {
                        onDismiss()
                    }
                },
                enabled = newName.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        }
    )
}

// Password strength types and UI

enum class PasswordStrengthLevel {
    TooWeak,
    Fair,
    Strong,
    VeryStrong
}

data class PasswordStrengthResult(
    val level: PasswordStrengthLevel = PasswordStrengthLevel.TooWeak,
    val description: String = "",
    val feedback: String = "",
    val isAcceptable: Boolean = false
)

@Composable
fun PasswordStrengthIndicator(strength: PasswordStrengthResult) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Strength bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val segmentCount = 4
            val filledSegments = when (strength.level) {
                PasswordStrengthLevel.TooWeak -> 1
                PasswordStrengthLevel.Fair -> 2
                PasswordStrengthLevel.Strong -> 3
                PasswordStrengthLevel.VeryStrong -> 4
            }
            val color = when (strength.level) {
                PasswordStrengthLevel.TooWeak -> MaterialTheme.colorScheme.error
                PasswordStrengthLevel.Fair -> MaterialTheme.colorScheme.tertiary
                PasswordStrengthLevel.Strong -> MaterialTheme.colorScheme.primary
                PasswordStrengthLevel.VeryStrong -> MaterialTheme.colorScheme.primary
            }

            repeat(segmentCount) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .padding(horizontal = 1.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (index < filledSegments) color else color.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.small
                    ) {}
                }
            }
        }

        // Strength description
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = strength.description,
                style = MaterialTheme.typography.labelSmall,
                color = when (strength.level) {
                    PasswordStrengthLevel.TooWeak -> MaterialTheme.colorScheme.error
                    PasswordStrengthLevel.Fair -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            if (strength.isAcceptable) {
                Text(
                    text = "OK",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Feedback for weak passwords
        if (strength.feedback.isNotEmpty()) {
            Text(
                text = strength.feedback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HelpLinkItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open link",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AccessibilityToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// Content Updates types and UI

enum class ContentUpdateType {
    Networks,
    Locales,
    Themes,
    Help
}

sealed class ContentUpdateStatus {
    data object UpToDate : ContentUpdateStatus()
    data class UpdatesAvailable(val types: List<ContentUpdateType>) : ContentUpdateStatus()
    data class CheckFailed(val error: String) : ContentUpdateStatus()
    data object Disabled : ContentUpdateStatus()
}

sealed class ContentApplyResult {
    data object NoUpdates : ContentApplyResult()
    data class Applied(val applied: List<ContentUpdateType>, val failed: List<ContentUpdateType>) : ContentApplyResult()
    data object Disabled : ContentApplyResult()
    data class Error(val error: String) : ContentApplyResult()
}

@Composable
fun ContentUpdatesSection(
    onCheckUpdates: suspend () -> ContentUpdateStatus?,
    onApplyUpdates: suspend () -> ContentApplyResult?
) {
    var updateStatus by remember { mutableStateOf<ContentUpdateStatus?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Content Updates",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Updates include new social networks, localization improvements, and themes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (isChecking || isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        updateStatus?.let { status ->
                            ContentUpdateStatusBadge(status = status)
                        } ?: Text(
                            text = "Not checked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Check button
                OutlinedButton(
                    onClick = {
                        isChecking = true
                        errorMessage = null
                        successMessage = null
                        coroutineScope.launch {
                            val result = onCheckUpdates()
                            updateStatus = result
                            when (result) {
                                is ContentUpdateStatus.UpToDate -> {
                                    successMessage = "Everything is up to date"
                                }
                                is ContentUpdateStatus.UpdatesAvailable -> {
                                    val typeNames = result.types.map { it.toDisplayName() }
                                    successMessage = "Updates available: ${typeNames.joinToString(", ")}"
                                }
                                is ContentUpdateStatus.CheckFailed -> {
                                    errorMessage = "Check failed: ${result.error}"
                                }
                                is ContentUpdateStatus.Disabled -> {
                                    errorMessage = "Content updates are disabled"
                                }
                                null -> {
                                    errorMessage = "Failed to check for updates"
                                }
                            }
                            isChecking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isChecking && !isApplying
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Check for Updates")
                }

                // Apply button (only when updates available)
                val hasUpdates = updateStatus is ContentUpdateStatus.UpdatesAvailable
                if (hasUpdates) {
                    Button(
                        onClick = {
                            isApplying = true
                            errorMessage = null
                            successMessage = null
                            coroutineScope.launch {
                                val result = onApplyUpdates()
                                when (result) {
                                    is ContentApplyResult.NoUpdates -> {
                                        successMessage = "No updates to apply"
                                    }
                                    is ContentApplyResult.Applied -> {
                                        if (result.failed.isEmpty()) {
                                            successMessage = "Applied ${result.applied.size} update(s)"
                                        } else {
                                            successMessage = "Applied ${result.applied.size}, failed ${result.failed.size}"
                                        }
                                    }
                                    is ContentApplyResult.Disabled -> {
                                        errorMessage = "Content updates are disabled"
                                    }
                                    is ContentApplyResult.Error -> {
                                        errorMessage = "Apply failed: ${result.error}"
                                    }
                                    null -> {
                                        errorMessage = "Failed to apply updates"
                                    }
                                }
                                // Reset status after applying
                                updateStatus = null
                                isApplying = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isChecking && !isApplying
                    ) {
                        if (isApplying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Apply Updates")
                    }
                }

                // Error message
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Success message
                successMessage?.let { success ->
                    Text(
                        text = success,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ContentUpdateStatusBadge(status: ContentUpdateStatus) {
    when (status) {
        is ContentUpdateStatus.UpToDate -> {
            Text(
                text = "Up to date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        is ContentUpdateStatus.UpdatesAvailable -> {
            Text(
                text = "${status.types.size} available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is ContentUpdateStatus.CheckFailed -> {
            Text(
                text = "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is ContentUpdateStatus.Disabled -> {
            Text(
                text = "Disabled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ContentUpdateType.toDisplayName(): String = when (this) {
    ContentUpdateType.Networks -> "Social Networks"
    ContentUpdateType.Locales -> "Languages"
    ContentUpdateType.Themes -> "Themes"
    ContentUpdateType.Help -> "Help Content"
}

// Certificate Pinning Section

@Composable
fun CertificatePinningSection(
    isEnabled: Boolean,
    onSetCertificate: (String) -> Unit
) {
    var showSetCertificateDialog by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider()

        Text(
            text = "Security",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Certificate Pinning",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (isEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Certificate pinning ensures the app only connects to relay servers presenting a specific certificate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Set Certificate button
                OutlinedButton(
                    onClick = { showSetCertificateDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Certificate")
                }

                // Clear Certificate button (only if enabled)
                if (isEnabled) {
                    TextButton(
                        onClick = { showClearConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear Certificate")
                    }
                }
            }
        }
    }

    if (showSetCertificateDialog) {
        SetCertificateDialog(
            onDismiss = { showSetCertificateDialog = false },
            onSetCertificate = { certPem ->
                onSetCertificate(certPem)
                showSetCertificateDialog = false
            }
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear Certificate?") },
            text = { Text("This will disable certificate pinning and allow connections to any valid relay server.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetCertificate("")
                        showClearConfirmation = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SetCertificateDialog(
    onDismiss: () -> Unit,
    onSetCertificate: (String) -> Unit
) {
    var certificateText by remember { mutableStateOf("") }

    val isValidPem = certificateText.trim().let { text ->
        text.startsWith("-----BEGIN CERTIFICATE-----") &&
        text.endsWith("-----END CERTIFICATE-----")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Certificate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste the certificate in PEM format. This is typically provided by your organization's IT department.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = certificateText,
                    onValueChange = { certificateText = it },
                    label = { Text("Certificate (PEM)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                if (certificateText.isNotEmpty() && !isValidPem) {
                    Text(
                        text = "Invalid PEM format. Must begin with '-----BEGIN CERTIFICATE-----'",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSetCertificate(certificateText.trim()) },
                enabled = isValidPem
            ) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
