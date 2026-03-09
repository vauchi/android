// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.FieldDisplay
import app.vauchi.ui.coreui.UiFieldVisibility
import app.vauchi.ui.coreui.UserAction
import app.vauchi.ui.coreui.VisibilityMode

/**
 * Renders a core FieldList component as rows with visibility controls.
 *
 * In ShowHide mode, each field has a visibility toggle icon.
 * In PerGroup mode, each field shows group chips for fine-grained control.
 */
@Composable
fun FieldListComponent(
    fields: List<FieldDisplay>,
    visibilityMode: VisibilityMode,
    availableGroups: List<String>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        fields.forEachIndexed { index, field ->
            FieldRow(
                field = field,
                visibilityMode = visibilityMode,
                availableGroups = availableGroups,
                onAction = onAction,
            )
            if (index < fields.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    field: FieldDisplay,
    visibilityMode: VisibilityMode,
    availableGroups: List<String>,
    onAction: (UserAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = field.value,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (visibilityMode == VisibilityMode.ShowHide) {
                val isVisible = field.visibility is UiFieldVisibility.Shown
                IconButton(onClick = {
                    onAction(
                        UserAction.FieldVisibilityChanged(
                            fieldId = field.id,
                            groupId = null,
                            visible = !isVisible,
                        ),
                    )
                }) {
                    Icon(
                        imageVector = if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isVisible) "Hide ${field.label}" else "Show ${field.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (visibilityMode == VisibilityMode.PerGroup && availableGroups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            val visibleGroups =
                when (val vis = field.visibility) {
                    is UiFieldVisibility.Groups -> vis.groups.toSet()
                    is UiFieldVisibility.Shown -> availableGroups.toSet()
                    is UiFieldVisibility.Hidden -> emptySet()
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableGroups.forEach { group ->
                    val isSelected = group in visibleGroups
                    AssistChip(
                        onClick = {
                            onAction(
                                UserAction.FieldVisibilityChanged(
                                    fieldId = field.id,
                                    groupId = group,
                                    visible = !isSelected,
                                ),
                            )
                        },
                        label = { Text(group) },
                    )
                }
            }
        }
    }
}
