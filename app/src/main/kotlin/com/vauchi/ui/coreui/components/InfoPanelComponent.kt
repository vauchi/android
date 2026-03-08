// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vauchi.ui.coreui.InfoItem

/**
 * Renders a core InfoPanel component as a styled surface with title and info items.
 */
@Composable
fun InfoPanelComponent(
    icon: String?,
    title: String,
    items: List<InfoItem>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                icon?.let {
                    Icon(
                        imageVector = resolveIcon(it),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }

            items.forEach { item ->
                InfoItemRow(item = item)
            }
        }
    }
}

@Composable
private fun InfoItemRow(item: InfoItem) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
    ) {
        item.icon?.let {
            Icon(
                imageVector = resolveIcon(it),
                contentDescription = null,
                modifier =
                    Modifier
                        .size(20.dp)
                        .padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Maps icon name strings from core to Material Icons.
 *
 * Core sends icon names as lowercase strings (e.g. "shield", "lock").
 * We map them to the best Material Icon match.
 */
internal fun resolveIcon(name: String): ImageVector =
    when (name.lowercase()) {
        "shield" -> Icons.Default.Shield
        "lock" -> Icons.Default.Lock
        "security" -> Icons.Default.Security
        "info" -> Icons.Default.Info
        "check", "check_circle" -> Icons.Default.CheckCircle
        "warning" -> Icons.Default.Warning
        "visibility_off" -> Icons.Default.VisibilityOff
        "refresh" -> Icons.Default.Refresh
        "people" -> Icons.Default.People
        "group" -> Icons.Default.Group
        "card" -> Icons.Default.ContactPage
        "eye" -> Icons.Default.Visibility
        "server" -> Icons.Default.Dns
        "key" -> Icons.Default.VpnKey
        "backup" -> Icons.Default.Backup
        "devices" -> Icons.Default.Devices
        "share" -> Icons.Default.Share
        "edit" -> Icons.Default.Edit
        else -> Icons.Default.Info
    }
