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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.Item
import app.vauchi.ui.coreui.ListItemAction
import app.vauchi.ui.coreui.ListItemActionKind
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.LocalizationManager

/**
 * Renders a core `Component.List` as a searchable list of items.
 *
 * Rows render eagerly: this component sits inside `ScreenRenderer`'s
 * vertically-scrollable Column, and Compose forbids nesting a
 * vertically-scrollable LazyColumn inside another vertically-scrollable
 * container (the parent passes an infinite-height constraint its
 * measure policy rejects — the Samsung S7 composition-time crash).
 * Acceptable only for short lists; large lists ship on Pinned-layout
 * screens where `ScreenRenderer` lazy-hosts the rows itself via
 * [ListSearchField] + [ListItemRow]
 * (2026-06-11-contacts-list-eager-render-anr).
 */
@Composable
fun ListComponent(
    componentId: String,
    items: List<Item>,
    searchable: Boolean,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (searchable) {
            ListSearchField(componentId = componentId, onAction = onAction)
        }

        for (item in items) {
            ListItemRow(componentId = componentId, item = item, onAction = onAction)
        }
    }
}

/** The list's search input — also lazy-hosted by Pinned screens. */
@Composable
internal fun ListSearchField(
    componentId: String,
    onAction: (UserAction) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    OutlinedTextField(
        value = "",
        onValueChange = { query ->
            onAction(UserAction.SearchChanged(componentId = componentId, query = query))
        },
        label = { Text(localizationManager.t("action.search")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
internal fun ListItemRow(
    componentId: String,
    item: Item,
    onAction: (UserAction) -> Unit,
) {
    ItemRow(
        item = item,
        onTap = {
            onAction(
                UserAction.ListItemSelected(
                    componentId = componentId,
                    itemId = item.id,
                ),
            )
        },
        onAction = { action ->
            onAction(
                UserAction.ListItemAction(
                    componentId = componentId,
                    itemId = item.id,
                    actionId = action.id,
                ),
            )
        },
    )
}

@Composable
private fun ItemRow(
    item: Item,
    onTap: () -> Unit,
    onAction: (ListItemAction) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
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
                    text = item.avatarInitials,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
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

        item.status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (item.actions.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier =
                        Modifier.semantics {
                            contentDescription = localizationManager.t("a11y.more_actions_for").replace("{name}", item.name)
                        },
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    item.actions.forEach { action ->
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

// TODO(HUMBLE): W/T, P2. Maps ListItemActionKind domain variants to platform
// icons. Fix: core provides icon_token per action. (see _private problem
// record 2026-07-06-mobile-domain-shell-violations)
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
