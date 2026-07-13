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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.DesignTokens
import app.vauchi.ui.coreui.InfoItem

/**
 * Renders a core InfoPanel component as a styled surface with title and info items.
 */
@Composable
fun InfoPanelComponent(
    icon: String?,
    title: String,
    items: List<InfoItem>,
    modifier: Modifier = Modifier,
    a11y: A11y? = null,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = a11y?.label ?: title },
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
                .padding(vertical = DesignTokens.DEFAULT.spacing.xs.dp),
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
// TODO(HUMBLE): W, P2. Maintains a domain icon-name catalog in the view layer
// ("group", "card", "qrcode", etc.). Fix: core emits icon_token mapped to
// platform catalog. (see _private problem record
// 2026-07-06-mobile-domain-shell-violations)
internal fun resolveIcon(name: String): ImageVector =
    when (name.lowercase()) {
        "shield" -> Icons.Default.Shield

        "lock" -> Icons.Default.Lock

        "security" -> Icons.Default.Security

        "info" -> Icons.Default.Info

        "check", "check_circle", "checkmark.circle" -> Icons.Default.CheckCircle

        "folder" -> Icons.Default.Folder

        "more" -> Icons.Default.MoreVert

        // Field-type glyphs (received-fields list on exchange success).
        "phone" -> Icons.Default.Phone

        "envelope" -> Icons.Default.Email

        "globe" -> Icons.Default.Language

        "mappin" -> Icons.Default.LocationOn

        "at" -> Icons.Default.AlternateEmail

        "gift" -> Icons.Default.CardGiftcard

        "tag" -> Icons.AutoMirrored.Filled.Label

        "warning" -> Icons.Default.Warning

        "visibility_off" -> Icons.Default.VisibilityOff

        "refresh" -> Icons.Default.Refresh

        "people" -> Icons.Default.People

        "group" -> Icons.Default.Group

        "card" -> Icons.Default.ContactPage

        "eye" -> Icons.Default.Visibility

        "server" -> Icons.Default.Dns

        "key" -> Icons.Default.VpnKey

        "backup", "drive" -> Icons.Default.Backup

        "id_card" -> Icons.Default.Badge

        "lifebuoy" -> Icons.Default.SupportAgent

        "swap" -> Icons.Default.SwapHoriz

        "checkmark.seal" -> Icons.Default.VerifiedUser

        "devices" -> Icons.Default.Devices

        "share" -> Icons.Default.Share

        "edit" -> Icons.Default.Edit

        // Exchange mode glyphs (mode-selection list).
        "qrcode" -> Icons.Default.QrCodeScanner

        "nfc" -> Icons.Default.Nfc

        "bump" -> Icons.Default.Sensors

        "shake" -> Icons.Default.Vibration

        "sparkles" -> Icons.Default.AutoAwesome

        "tap" -> Icons.Default.TouchApp

        "gesture" -> Icons.Default.Gesture

        "link" -> Icons.Default.Link

        "cable" -> Icons.Default.Cable

        else -> Icons.Default.Info
    }
