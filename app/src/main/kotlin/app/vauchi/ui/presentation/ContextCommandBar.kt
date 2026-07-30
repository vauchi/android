// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ContextCommandBar(
    surfaceId: String,
    bar: ContextBar?,
    windowClass: WindowClass,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bar == null) return
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = if (windowClass == WindowClass.Compact) 720.dp else 640.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bar.back?.let {
                RoleButton(
                    action = it,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = {
                        onEvent(PresentationEvent.BackRequested(surfaceId))
                    },
                )
            }
            bar.navigation?.let {
                RoleButton(
                    action = it,
                    icon = Icons.Default.Menu,
                    onClick = {
                        onEvent(
                            PresentationEvent.ActionActivated(
                                surfaceId,
                                it.interactionId,
                            ),
                        )
                    },
                )
            }
            bar.primary?.let {
                Button(
                    onClick = {
                        onEvent(
                            PresentationEvent.ActionActivated(
                                surfaceId,
                                it.interactionId,
                            ),
                        )
                    },
                    enabled = it.enabled,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .semantics {
                                contentDescription = it.accessibilityLabel
                            },
                ) {
                    if (it.shortcut == "undo") {
                        Icon(
                            Icons.Default.Undo,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(it.label)
                }
            }
            bar.secondary?.let {
                RoleButton(
                    action = it,
                    icon = Icons.Default.MoreHoriz,
                    onClick = {
                        onEvent(
                            PresentationEvent.ActionActivated(
                                surfaceId,
                                it.interactionId,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RoleButton(
    action: ActionSpec,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = action.enabled,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = action.accessibilityLabel
                },
    ) {
        Icon(icon, contentDescription = null)
    }
}
