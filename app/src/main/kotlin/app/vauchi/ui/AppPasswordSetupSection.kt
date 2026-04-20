// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vauchi.ui.model.PasswordStrengthResult
import app.vauchi.util.LocalizationManager

/**
 * Settings section for setting up an app password.
 *
 * Prerequisite for duress PIN — without an app password, there is no
 * PIN entry screen for the duress PIN to work through.
 */
@Composable
fun AppPasswordSetupSection(
    onSetupPassword: (String) -> Unit,
    onCheckStrength: (String) -> PasswordStrengthResult = { PasswordStrengthResult() },
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = localizationManager.t("app_password.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = localizationManager.t("app_password.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(localizationManager.t("app_password.set_button"))
            }
        }
    }

    if (showDialog) {
        AppPasswordSetupDialog(
            onDismiss = { showDialog = false },
            onConfirm = { password ->
                onSetupPassword(password)
                showDialog = false
            },
            onCheckStrength = onCheckStrength,
        )
    }
}

@Composable
private fun AppPasswordSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onCheckStrength: (String) -> PasswordStrengthResult,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val strength = remember(password) { onCheckStrength(password) }
    val passwordsMatch = password == confirmPassword && password.isNotEmpty()
    val isValid = passwordsMatch && password.length >= MIN_PASSCODE_LENGTH
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizationManager.t("app_password.dialog_title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(localizationManager.t("app_password.password_label")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (password.isNotEmpty()) {
                    Text(
                        text =
                            localizationManager.t(
                                "app_password.strength",
                                mapOf("strength" to strength.description),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = {
                        Text(
                            localizationManager.t("app_password.confirm_password_label"),
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                    Text(
                        text = localizationManager.t("app_password.passwords_mismatch"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = isValid,
            ) {
                Text(localizationManager.t("app_password.dialog_confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizationManager.t("action.cancel"))
            }
        },
    )
}
