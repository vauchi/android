// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vauchi.ui.coreui.TextStyle

/**
 * Renders a core Text component with the appropriate Material3 typography.
 */
@Composable
fun TextComponent(
    content: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val typography =
        when (style) {
            TextStyle.Title -> MaterialTheme.typography.headlineSmall
            TextStyle.Subtitle -> MaterialTheme.typography.titleMedium
            TextStyle.Body -> MaterialTheme.typography.bodyLarge
            TextStyle.Caption -> MaterialTheme.typography.bodySmall
        }

    val color =
        when (style) {
            TextStyle.Title -> MaterialTheme.colorScheme.onSurface
            TextStyle.Subtitle -> MaterialTheme.colorScheme.onSurfaceVariant
            TextStyle.Body -> MaterialTheme.colorScheme.onSurface
            TextStyle.Caption -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Text(
        text = content,
        style = typography,
        color = color,
        modifier = modifier,
    )
}
