// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.ActionListItem
import app.vauchi.ui.coreui.Section
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core
 * [Component.SectionedActionList][app.vauchi.ui.coreui.Component.SectionedActionList]
 * as a vertically stacked native Material 3 sectioned list — one
 * surface "card" per section with a label header and rounded rows.
 *
 * Distinct from [ActionListComponent] (flat menu). The structural
 * grouping lives at variant level so this renderer never has to fold
 * unrelated sections together.
 *
 * Rows reuse [ActionListItem] so the row layout matches
 * [ActionListComponent] (icon / label / detail / chevron).
 *
 * Per shell-purity investigation 2026-05-28 (core 0.51.21 / core!990).
 */
@Composable
fun SectionedActionListComponent(
    componentId: String,
    sections: List<Section>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { testTag = componentId },
    ) {
        sections.forEachIndexed { index, section ->
            SectionedActionSection(
                componentId = componentId,
                section = section,
                onAction = onAction,
            )
            if (index < sections.lastIndex) {
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionedActionSection(
    componentId: String,
    section: Section,
    onAction: (UserAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = section.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(PaddingValues(vertical = 4.dp))) {
                section.items.forEachIndexed { index, item ->
                    SectionedActionRow(
                        componentId = componentId,
                        item = item,
                        onAction = onAction,
                    )
                    if (index < section.items.lastIndex) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .size(width = 0.dp, height = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionedActionRow(
    componentId: String,
    item: ActionListItem,
    onAction: (UserAction) -> Unit,
) {
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
                }.padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics {
                    contentDescription = item.a11y?.label ?: item.label
                    item.a11y?.hint?.let { stateDescription = it }
                    role = Role.Button
                },
    ) {
        item.icon?.let {
            Icon(
                imageVector = resolveIcon(it),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        item.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
