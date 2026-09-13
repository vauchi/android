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

/** Where the action is drawn, which decides how much weight it may take. */
internal enum class ActionPlacement {
    /** The context bar, whose primary slot is the screen's one call to action. */
    CommandBar,

    /** The action-menu sheet, where no entry may read as the screen's primary. */
    Menu,
}

/**
 * Maps a Core-sent action tone, and where it is drawn, to the button
 * emphasis and accent colour that render it. A pure function, not a
 * composable, so the rules this encodes — serious is outlined and
 * warning-coloured, never filled or error red; nothing in the action menu
 * competes with the context bar's primary — are unit-testable without a
 * Compose host.
 */
internal fun toneColors(
    tone: ActionTone,
    colorScheme: ColorScheme,
    statusColors: StatusColors,
    placement: ActionPlacement = ActionPlacement.CommandBar,
): ActionToneStyle =
    when (tone) {
        ActionTone.Standard ->
            ActionToneStyle(
                // A sheet of filled primaries has no hierarchy: the canvas
                // draws these affordances outlined and keeps the fill for the
                // context bar's single primary.
                if (placement == ActionPlacement.Menu) {
                    ActionToneEmphasis.Outlined
                } else {
                    ActionToneEmphasis.Filled
                },
                colorScheme.primary,
            )
        ActionTone.Destructive -> ActionToneStyle(ActionToneEmphasis.Filled, colorScheme.error)
        ActionTone.Serious -> ActionToneStyle(ActionToneEmphasis.Outlined, statusColors.warning)
    }
