// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.ContactItem
import app.vauchi.ui.coreui.ListItemAction
import app.vauchi.ui.coreui.ListItemActionKind
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core ContactList component as a searchable list of contacts.
 */
@Composable
fun ContactListComponent(
    componentId: String,
    contacts: List<ContactItem>,
    searchable: Boolean,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (searchable) {
            OutlinedTextField(
                value = "",
                onValueChange = { query ->
                    onAction(UserAction.SearchChanged(componentId = componentId, query = query))
                },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(contacts, key = { it.id }) { contact ->
                ContactRow(
                    contact = contact,
                    onTap = {
                        onAction(
                            UserAction.ListItemSelected(
                                componentId = componentId,
                                itemId = contact.id,
                            ),
                        )
                    },
                    onAction = { action ->
                        onAction(
                            UserAction.ListItemAction(
                                componentId = componentId,
                                itemId = contact.id,
                                actionId = action.id,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactItem,
    onTap: () -> Unit,
    onAction: (ListItemAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onTap() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = contact.avatarInitials,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            contact.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        contact.status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (contact.actions.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "More actions for ${contact.name}"
                        },
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    contact.actions.forEach { action ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(iconFor(action.kind), contentDescription = null)
                            },
                            text = { Text(action.label) },
                            onClick = {
                                menuOpen = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun iconFor(kind: ListItemActionKind): ImageVector =
    when (kind) {
        ListItemActionKind.Archive -> Icons.Default.Archive
        ListItemActionKind.Unarchive -> Icons.Default.Unarchive
        ListItemActionKind.Hide -> Icons.Default.VisibilityOff
        ListItemActionKind.Unhide -> Icons.Default.Visibility
        ListItemActionKind.Delete -> Icons.Default.Delete
        ListItemActionKind.Undelete -> Icons.Default.Undo
        ListItemActionKind.Custom, ListItemActionKind.Unknown -> Icons.Default.MoreVert
    }
