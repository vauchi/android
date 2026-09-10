// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.vauchi.ui.theme.StatusColors

internal enum class ActionToneEmphasis {
    Filled,
    Outlined,
}

internal data class ActionToneStyle(
    val emphasis: ActionToneEmphasis,
    val accent: Color,
)

/**
 * Maps a Core-sent action tone to the button emphasis and accent colour
 * that render it. A pure function, not a composable, so the rule this
 * encodes — serious is outlined and warning-coloured, never filled or
 * error red — is unit-testable without a Compose host.
 */
internal fun toneColors(
    tone: ActionTone,
    colorScheme: ColorScheme,
    statusColors: StatusColors,
): ActionToneStyle =
    when (tone) {
        ActionTone.Standard -> ActionToneStyle(ActionToneEmphasis.Filled, colorScheme.primary)
        ActionTone.Destructive -> ActionToneStyle(ActionToneEmphasis.Filled, colorScheme.error)
        ActionTone.Serious -> ActionToneStyle(ActionToneEmphasis.Outlined, statusColors.warning)
    }
