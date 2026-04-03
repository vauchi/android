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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uniffi.vauchi_platform.MobileShredStatus

/**
 * Settings section for scheduled data shredding with a grace period.
 *
 * Soft shred starts a 7-day countdown. During this period the user
 * can cancel. After the grace period, hard shred executes and
 * irreversibly destroys all data.
 */
@Composable
fun ScheduledShredSection(
    shredStatus: MobileShredStatus,
    onSchedule: () -> Unit,
    onCancel: () -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showScheduleConfirm by remember { mutableStateOf(false) }
    var showExecuteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Scheduled Deletion",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(4.dp))

            when (shredStatus) {
                is MobileShredStatus.None -> {
                    Text(
                        text = "Schedule a 7-day grace period before permanent data destruction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showScheduleConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text("Schedule Deletion")
                    }
                }

                is MobileShredStatus.Scheduled -> {
                    val days = shredStatus.remainingSecs / 86400UL
                    val hours = (shredStatus.remainingSecs % 86400UL) / 3600UL
                    Text(
                        text = "Deletion scheduled — ${days}d ${hours}h remaining",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onCancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel Deletion")
                    }
                    if (shredStatus.remainingSecs == 0UL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showExecuteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Text("Execute Deletion Now")
                        }
                    }
                }

                is MobileShredStatus.Executed -> {
                    Text(
                        text = "All data has been permanently destroyed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showScheduleConfirm) {
        AlertDialog(
            onDismissRequest = { showScheduleConfirm = false },
            title = { Text("Schedule Deletion") },
            text = {
                Text(
                    "This starts a 7-day countdown. After the grace period, " +
                        "all data will be irreversibly destroyed. You can cancel " +
                        "during the grace period.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSchedule()
                    showScheduleConfirm = false
                }) {
                    Text("Schedule", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScheduleConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showExecuteConfirm) {
        AlertDialog(
            onDismissRequest = { showExecuteConfirm = false },
            title = { Text("Execute Deletion") },
            text = {
                Text(
                    "This will permanently destroy ALL data including your identity, " +
                        "contacts, and encryption keys. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onExecute()
                    showExecuteConfirm = false
                }) {
                    Text("Destroy Everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExecuteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
