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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.LocalizationManager

/**
 * Renders a core EditableText component that toggles between display and edit mode.
 */
@Composable
fun EditableTextComponent(
    componentId: String,
    label: String,
    value: String,
    editing: Boolean,
    validationError: String?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    onAction(UserAction.TextChanged(componentId = componentId, value = newValue))
                },
                label = { Text(label) },
                isError = validationError != null,
                supportingText =
                    validationError?.let {
                        { Text(it) }
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )

                // TODO(HUMBLE): T, P1. Mints "{id}:edit" action id. Fix: core
                // supplies explicit edit_action_id. (see _private problem record
                // 2026-07-06-mobile-domain-shell-violations)
                IconButton(
                    onClick = {
                        onAction(UserAction.ActionPressed(actionId = "$componentId:edit"))
                    },
                    modifier = Modifier.semantics { contentDescription = localizationManager.t("a11y.edit_field").replace("{label}", label) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
