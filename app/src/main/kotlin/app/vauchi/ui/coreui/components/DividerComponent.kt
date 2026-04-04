// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders a core Divider component — a horizontal rule
 * used to visually separate groups of content.
 */
@Composable
fun DividerComponent(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
    )
}
