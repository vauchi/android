// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.SettingsItem
import app.vauchi.ui.coreui.SettingsItemKind
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core SettingsGroup component as a card with label header and settings items.
 */
@Composable
fun SettingsGroupComponent(
    componentId: String,
    label: String,
    items: List<SettingsItem>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            items.forEach { item ->
                SettingsItemRow(
                    componentId = componentId,
                    item = item,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun SettingsItemRow(
    componentId: String,
    item: SettingsItem,
    onAction: (UserAction) -> Unit,
) {
    when (item.kind) {
        is SettingsItemKind.Toggle -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAction(
                                UserAction.SettingsToggled(
                                    componentId = componentId,
                                    itemId = item.id,
                                ),
                            )
                        }.padding(vertical = 8.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = item.kind.enabled,
                    onCheckedChange = null, // handled by row click
                )
            }
        }

        is SettingsItemKind.Value -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.kind.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SettingsItemKind.Link -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAction(
                                UserAction.ListItemSelected(
                                    componentId = componentId,
                                    itemId = item.id,
                                ),
                            )
                        }.padding(vertical = 8.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                item.kind.detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.padding(start = 4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SettingsItemKind.Destructive -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAction(
                                UserAction.ListItemSelected(
                                    componentId = componentId,
                                    itemId = item.id,
                                ),
                            )
                        }.padding(vertical = 8.dp),
            ) {
                Text(
                    text = item.kind.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
