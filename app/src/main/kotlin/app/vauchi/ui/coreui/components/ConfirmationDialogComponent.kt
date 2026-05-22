// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.LocalizationManager

/**
 * Renders a core ConfirmationDialog component as a card with title,
 * message, and confirm/cancel buttons.
 */
@Composable
fun ConfirmationDialogComponent(
    componentId: String,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = {
                        onAction(UserAction.ActionPressed(actionId = "cancel"))
                    },
                ) {
                    Text(localizationManager.t("action.cancel"))
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (destructive) {
                    Button(
                        onClick = {
                            onAction(UserAction.ActionPressed(actionId = "confirm"))
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        Text(confirmText)
                    }
                } else {
                    Button(
                        onClick = {
                            onAction(UserAction.ActionPressed(actionId = "confirm"))
                        },
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
