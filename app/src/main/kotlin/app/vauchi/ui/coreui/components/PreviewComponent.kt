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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.DesignTokens
import app.vauchi.ui.coreui.Field
import app.vauchi.ui.coreui.PreviewVariant
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.LocalizationManager

/**
 * Renders a core `Component.Preview` as a Material3 Card.
 *
 * Shows the preview with name, fields, and optional variant selector.
 */
@Composable
fun PreviewComponent(
    name: String,
    fields: List<Field>,
    variants: List<PreviewVariant>,
    selectedVariant: String?,
    visibleFields: List<Field>,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    avatarData: List<Int>? = null,
    a11y: A11y? = null,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Column(modifier = modifier.fillMaxWidth()) {
        // Variant selector chips. Core's `UserAction::GroupViewSelected`
        // (kept its old name in Tier 0; payload `group_name` carries the
        // variant id on the wire).
        if (variants.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
            ) {
                FilterChip(
                    selected = selectedVariant == null,
                    onClick = { onAction(UserAction.GroupViewSelected(groupName = null)) },
                    label = { Text(localizationManager.t("preview.filter_all")) },
                    modifier = Modifier.padding(end = 8.dp),
                )
                variants.forEach { variant ->
                    FilterChip(
                        selected = selectedVariant == variant.variantId,
                        onClick = {
                            onAction(UserAction.GroupViewSelected(groupName = variant.variantId))
                        },
                        // Chip label is the variant identifier (was `group_name`
                        // pre-Tier-1) — that's the tab label per the design spec.
                        // `display_name` populates the card header instead, after
                        // the user picks a variant.
                        label = { Text(variant.variantId) },
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
            if (selectedVariant != null) {
                variants.find { it.variantId == selectedVariant }?.displayName ?: name
            } else {
                name
            }

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = a11y?.label ?: "Preview: $displayName" },
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
                            contentDescription = localizationManager.t("a11y.avatar_for").replace("{name}", displayName),
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
                        PreviewFieldRow(field = field)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewFieldRow(field: Field) {
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
