// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Pure render model for the persistent navigation bar. Core sends an
 * empty item list to mean "no bar" (e.g. a locked app) rather than a
 * separate visibility flag, so [isVisible] is derived rather than carried.
 */
internal data class NavigationBarModel(
    val items: List<NavigationItem>,
) {
    val isVisible: Boolean
        get() = items.isNotEmpty()

    val selectedIndex: Int?
        get() = items.indexOfFirst { it.selected }.takeIf { it >= 0 }

    /**
     * The exchange destination is identified by its icon token, not its
     * position — Core places it centrally today, but the token stays the
     * stable contract if a future reorder moves it.
     */
    val centerItemIndex: Int?
        get() = items.indexOfFirst { it.iconToken == EXCHANGE_ICON_TOKEN }.takeIf { it >= 0 }

    val centerItem: NavigationItem?
        get() = centerItemIndex?.let(items::get)

    companion object {
        const val EXCHANGE_ICON_TOKEN = "qrcode"
    }
}

private val ExchangeCircleSize = 64.dp

/**
 * Persistent bottom navigation, replacing the navigation overlay as the
 * primary way to switch destinations. Empty [navigation] items hide the
 * bar entirely (a locked app has no destinations to offer); the overlay
 * path in [PresentationOverlay] keeps working unchanged for the action
 * menu.
 */
@Composable
fun PersistentNavigationBar(
    surfaceId: String,
    navigation: NavigationSpec?,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = NavigationBarModel(navigation?.items.orEmpty())
    if (!model.isVisible) return

    val tokens = LocalPresentationTokens.current
    NavigationBar(modifier = modifier) {
        model.items.forEachIndexed { index, item ->
            if (index == model.centerItemIndex) {
                ExchangeNavigationItem(surfaceId, item, index, tokens, onEvent)
            } else {
                StandardNavigationItem(surfaceId, item, index, tokens, onEvent)
            }
        }
    }
}

@Composable
private fun RowScope.StandardNavigationItem(
    surfaceId: String,
    item: NavigationItem,
    index: Int,
    tokens: PresentationTokens,
    onEvent: (PresentationEvent) -> Unit,
) {
    NavigationBarItem(
        selected = item.selected,
        onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, item.interactionId)) },
        icon = {
            BadgedBox(badge = { NavigationBadge(item.badgeCount) }) {
                item.iconToken?.let {
                    Icon(navigationIcon(it), contentDescription = null)
                }
            }
        },
        label = { Text(item.label) },
        modifier =
            Modifier
                .heightIn(min = minimumTouchTarget(tokens))
                .testTag("navbar.item.$index")
                .semantics { contentDescription = item.accessibilityLabel },
    )
}

/**
 * Option A of the design: a raised 64dp accent circle with the label
 * beneath, standing out from the flat [NavigationBarItem]s around it so
 * the exchange action reads as the primary one on the bar.
 */
@Composable
private fun RowScope.ExchangeNavigationItem(
    surfaceId: String,
    item: NavigationItem,
    index: Int,
    tokens: PresentationTokens,
    onEvent: (PresentationEvent) -> Unit,
) {
    val accent = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, item.interactionId)) },
            shape = CircleShape,
            color = accent,
            modifier =
                Modifier
                    .size(maxOf(ExchangeCircleSize, minimumTouchTarget(tokens)))
                    .testTag("navbar.item.$index")
                    .semantics { contentDescription = item.accessibilityLabel },
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BadgedBox(badge = { NavigationBadge(item.badgeCount) }) {
                    item.iconToken?.let {
                        Icon(navigationIcon(it), contentDescription = null, tint = contentColorFor(accent))
                    }
                }
            }
        }
        Text(item.label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NavigationBadge(count: Int) {
    if (count > 0) {
        Badge { Text(count.toString()) }
    }
}
