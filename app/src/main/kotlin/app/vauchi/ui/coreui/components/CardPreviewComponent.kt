// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.DesignTokens
import app.vauchi.ui.coreui.FieldDisplay
import app.vauchi.ui.coreui.GroupCardView
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core CardPreview component as a Material3 Card.
 *
 * Shows the contact card with name, fields, and optional group view selector.
 */
@Composable
fun CardPreviewComponent(
    name: String,
    fields: List<FieldDisplay>,
    groupViews: List<GroupCardView>,
    selectedGroup: String?,
    visibleFields: List<FieldDisplay>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    avatarData: List<Int>? = null,
    a11y: A11y? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Group view selector chips
        if (groupViews.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
            ) {
                FilterChip(
                    selected = selectedGroup == null,
                    onClick = { onAction(UserAction.GroupViewSelected(groupName = null)) },
                    label = { Text("All") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                groupViews.forEach { groupView ->
                    FilterChip(
                        selected = selectedGroup == groupView.groupName,
                        onClick = {
                            onAction(UserAction.GroupViewSelected(groupName = groupView.groupName))
                        },
                        label = { Text(groupView.groupName) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        // Card
        // G1 (ADR-021/043): use the pre-filtered list emitted by core's
        // `build_visible_fields` helper. Replaces the previous frontend
        // fallback (`?: fields`) which leaked Hidden fields into the preview.
        val displayFields = visibleFields

        val displayName =
            if (selectedGroup != null) {
                groupViews.find { it.groupName == selectedGroup }?.displayName ?: name
            } else {
                name
            }

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = a11y?.label ?: "Card preview: $displayName" },
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                // Avatar
                val avatarBitmap =
                    remember(avatarData) {
                        avatarData?.let { data ->
                            val bytes = ByteArray(data.size) { data[it].toByte() }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }

                Box(
                    modifier =
                        Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors =
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                ),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = "Avatar for $displayName",
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )

                if (displayFields.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    displayFields.forEach { field ->
                        CardFieldRow(field = field)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardFieldRow(field: FieldDisplay) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.DEFAULT.spacing.xs.dp),
    ) {
        Column {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = field.value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
