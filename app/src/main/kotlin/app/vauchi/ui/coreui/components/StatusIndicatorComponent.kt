// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.Status
import app.vauchi.ui.theme.LocalStatusColors

/**
 * Renders a core StatusIndicator component as a read-only indicator
 * with icon, title, detail, and a colored status badge.
 */
@Composable
fun StatusIndicatorComponent(
    icon: String?,
    title: String,
    detail: String?,
    status: Status,
    statusLabel: String,
    a11y: A11y? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics {
                    contentDescription = a11y?.label ?: title
                    a11y?.hint?.let { stateDescription = it }
                },
    ) {
        icon?.let {
            Icon(
                imageVector = resolveIcon(it),
                contentDescription = a11y?.label ?: title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        StatusBadge(status = status, statusLabel = statusLabel)
    }
}

@Composable
private fun StatusBadge(
    status: Status,
    statusLabel: String,
) {
    val statusColors = LocalStatusColors.current
    // Closed enum selects presentation color only; core owns the label.
    val color =
        when (status) {
            Status.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
            Status.InProgress -> statusColors.info
            Status.Success -> statusColors.success
            Status.Failed -> MaterialTheme.colorScheme.error
            Status.Warning -> statusColors.warning
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
