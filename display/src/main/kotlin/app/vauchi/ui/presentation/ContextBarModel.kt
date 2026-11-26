// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** One of Core's context-bar slots that draws as an icon in the surface's title row. */
internal enum class ContextBarSlot {
    Back,
    Navigation,
    Secondary,
    Info,
}

/**
 * Pure render model for where the surface's title row draws Core's
 * context bar, kept out of the composable so [ContextBarModelTest] can
 * pin it without composing. The row carries the same slots the row above
 * the tab bar used to (vauchi/private#479, that row retired by #534):
 * back leads, then the navigation launcher — Core may send both, same as
 * the retired row did — left out only while the navigation is already on
 * screen as a tab bar. Info and actions trail, info first. Primary is not
 * a title-row slot: it draws as a full-width button under the surface
 * content instead.
 */
internal data class ContextBarModel(
    val bar: ContextBar?,
    val navigationShown: Boolean = false,
) {
    val leadingSlots: List<ContextBarSlot>
        get() {
            val bar = bar ?: return emptyList()
            return buildList {
                if (bar.back != null) add(ContextBarSlot.Back)
                if (bar.navigation != null && !navigationShown) add(ContextBarSlot.Navigation)
            }
        }

    val trailingSlots: List<ContextBarSlot>
        get() {
            val bar = bar ?: return emptyList()
            return buildList {
                if (bar.info != null) add(ContextBarSlot.Info)
                if (bar.secondary != null) add(ContextBarSlot.Secondary)
            }
        }

    val hasPrimary: Boolean
        get() = bar?.primary != null
}

private fun ContextBarSlot.icon(): ImageVector =
    when (this) {
        ContextBarSlot.Back -> Icons.AutoMirrored.Filled.ArrowBack
        ContextBarSlot.Navigation -> Icons.Default.Menu
        ContextBarSlot.Secondary -> Icons.Default.MoreHoriz
        ContextBarSlot.Info -> Icons.Outlined.Info
    }

private fun ContextBarSlot.testTag(): String =
    when (this) {
        ContextBarSlot.Back -> "contextbar.back"
        ContextBarSlot.Navigation -> "contextbar.navigation"
        ContextBarSlot.Secondary -> "contextbar.secondary"
        ContextBarSlot.Info -> "contextbar.info"
    }

/**
 * One title-row icon button for a context-bar slot. A bare icon with no
 * visible label under it: the title row is the platform's conventional
 * spot for these controls (Material 3 top app bar), unlike the retired
 * row above the tab bar where #479 found bare icons unreadable.
 */
@Composable
internal fun ContextBarIconButton(
    action: ActionSpec,
    slot: ContextBarSlot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = minimumTouchTarget(LocalPresentationTokens.current)
    IconButton(
        onClick = onClick,
        enabled = action.enabled,
        modifier =
            modifier
                .heightIn(min = target)
                .widthIn(min = target)
                .testTag(slot.testTag())
                .semantics { contentDescription = action.accessibilityLabel },
    ) {
        Icon(slot.icon(), contentDescription = null)
    }
}
