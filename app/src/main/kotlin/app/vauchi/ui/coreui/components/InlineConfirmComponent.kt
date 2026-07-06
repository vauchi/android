// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core InlineConfirm component as an inline warning with confirm/cancel buttons.
 */
@Composable
fun InlineConfirmComponent(
    componentId: String,
    warning: String,
    confirmText: String,
    cancelText: String,
    destructive: Boolean,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            ) {
                // TODO(HUMBLE): T, P1. Mints "{id}:cancel" action id. Fix:
                // core supplies explicit cancel_action_id. (see _private problem
                // record 2026-07-06-mobile-domain-shell-violations)
                OutlinedButton(
                    onClick = {
                        onAction(UserAction.ActionPressed(actionId = "$componentId:cancel"))
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics { contentDescription = cancelText },
                ) {
                    Text(cancelText)
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (destructive) {
                    // TODO(HUMBLE): T, P1. Mints "{id}:confirm" action id. Fix:
                    // core supplies explicit confirm_action_id. (see _private
                    // problem record 2026-07-06-mobile-domain-shell-violations)
                    Button(
                        onClick = {
                            onAction(UserAction.ActionPressed(actionId = "$componentId:confirm"))
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        modifier =
                            Modifier
                                .weight(1f)
                                .semantics { contentDescription = confirmText },
                    ) {
                        Text(confirmText)
                    }
                } else {
                    Button(
                        onClick = {
                            onAction(UserAction.ActionPressed(actionId = "$componentId:confirm"))
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .semantics { contentDescription = confirmText },
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
