// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.coreui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vauchi.ui.coreui.ContactItem
import com.vauchi.ui.coreui.UserAction

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAction(
                                    UserAction.ListItemSelected(
                                        componentId = componentId,
                                        itemId = contact.id,
                                    ),
                                )
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
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
                }
            }
        }
    }
}
