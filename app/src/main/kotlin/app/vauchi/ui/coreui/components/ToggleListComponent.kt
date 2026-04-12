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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.ToggleItem
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core ToggleList component as a column of checkbox rows.
 */
@Composable
fun ToggleListComponent(
    componentId: String,
    label: String,
    items: List<ToggleItem>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    a11y: A11y? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = a11y?.label ?: label },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription =
                                item.a11y?.label
                                    ?: "${item.label}, ${if (item.selected) "selected" else "not selected"}"
                        }.clickable(role = Role.Checkbox) {
                            onAction(
                                UserAction.ItemToggled(
                                    componentId = componentId,
                                    itemId = item.id,
                                ),
                            )
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = null, // handled by row click
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    item.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
