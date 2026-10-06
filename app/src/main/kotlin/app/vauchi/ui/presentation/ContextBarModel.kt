// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One of Core's four context-bar slots. */
enum class ContextBarSlot(
    /**
     * The launchers open a menu, and their icons alone were not understood
     * (vauchi/private#479); the back arrow is the platform's own.
     */
    val showsLabel: Boolean,
) {
    Back(showsLabel = false),
    Navigation(showsLabel = true),
    Primary(showsLabel = false),
    Secondary(showsLabel = true),
    Info(showsLabel = true),
}

/**
 * Pure render model for the context bar, kept out of the composable so
 * [ContextBarModelTest] can pin it. An absent action takes no space, and
 * while the navigation is on screen as a tab bar its launcher is left out:
 * both open the same destinations, and a second control for one list was
 * the one readers could not name.
 */
internal data class ContextBarModel(
    val bar: ContextBar?,
    val navigationShown: Boolean = false,
) {
    val slots: List<ContextBarSlot>
        get() {
            val bar = bar ?: return emptyList()
            return buildList {
                if (bar.back != null) add(ContextBarSlot.Back)
                if (bar.navigation != null && !navigationShown) add(ContextBarSlot.Navigation)
                if (bar.primary != null) add(ContextBarSlot.Primary)
                if (bar.secondary != null) add(ContextBarSlot.Secondary)
                if (bar.info != null) add(ContextBarSlot.Info)
            }
        }

    val isVisible: Boolean
        get() = slots.isNotEmpty()

    /** The primary button fills the row; without one a gap keeps Back leading and the launchers trailing. */
    val needsFlexibleGap: Boolean
        get() = isVisible && ContextBarSlot.Primary !in slots
}

/**
 * Core's context bar as one row on the same surface as the tab bar below
 * it, so the two read as a single bottom area rather than a card stacked
 * on a bar.
 */
@Composable
fun ContextCommandBar(
    surfaceId: String,
    bar: ContextBar?,
    windowClass: WindowClass,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier = Modifier,
    navigationShown: Boolean = false,
) {
    val model = ContextBarModel(bar, navigationShown)
    if (!model.isVisible || bar == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NavigationBarDefaults.containerColor,
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier =
                    Modifier
                        .widthIn(max = if (windowClass == WindowClass.Compact) 720.dp else 640.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ContextBarSlot.Back in model.slots) {
                    bar.back?.let {
                        RoleButton(
                            action = it,
                            slot = ContextBarSlot.Back,
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            tag = "contextbar.back",
                            onClick = { onEvent(PresentationEvent.BackRequested(surfaceId)) },
                        )
                    }
                }
                if (ContextBarSlot.Navigation in model.slots) {
                    bar.navigation?.let {
                        RoleButton(
                            action = it,
                            slot = ContextBarSlot.Navigation,
                            icon = Icons.Default.Menu,
                            tag = "contextbar.navigation",
                            onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, it.interactionId)) },
                        )
                    }
                }
                bar.primary?.let {
                    Button(
                        onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, it.interactionId)) },
                        enabled = it.enabled,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .testTag("contextbar.primary")
                                .semantics { contentDescription = it.accessibilityLabel },
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
                if (model.needsFlexibleGap) {
                    Spacer(modifier = Modifier.weight(1f))
                }
                bar.secondary?.let {
                    RoleButton(
                        action = it,
                        slot = ContextBarSlot.Secondary,
                        icon = Icons.Default.MoreHoriz,
                        tag = "contextbar.secondary",
                        onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, it.interactionId)) },
                    )
                }
                bar.info?.let {
                    RoleButton(
                        action = it,
                        slot = ContextBarSlot.Info,
                        icon = Icons.Outlined.Info,
                        tag = "contextbar.info",
                        onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, it.interactionId)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleButton(
    action: ActionSpec,
    slot: ContextBarSlot,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    val target = minimumTouchTarget(LocalPresentationTokens.current)
    val control =
        Modifier
            .heightIn(min = target)
            .widthIn(min = target)
            .testTag(tag)
            .semantics { contentDescription = action.accessibilityLabel }
    if (slot.showsLabel) {
        TextButton(onClick = onClick, enabled = action.enabled, modifier = control) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null)
                Text(
                    action.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        IconButton(onClick = onClick, enabled = action.enabled, modifier = control) {
            Icon(icon, contentDescription = null)
        }
    }
}
