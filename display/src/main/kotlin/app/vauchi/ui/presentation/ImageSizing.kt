// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which dimension governs a [PresentationNode.Image]'s square-or-natural
 * size, so [PresentationNodeRenderer] can turn the decision into a
 * `Modifier` without re-deriving it inline.
 */
internal sealed interface ImageDimension {
    /** Circular or no-bitmap fallback with no `size` sent: the touch-target floor. */
    data object TouchTarget : ImageDimension

    /** Natural (non-circular) bitmap with no `size` sent: fills the row. */
    data object NaturalWidth : ImageDimension

    /** Core sent `size`: a square capped at that many dp, whatever the shape or bitmap. */
    data class Fixed(
        val side: Dp,
    ) : ImageDimension
}

/**
 * Picks the dimension for a [PresentationNode.Image]. Core's optional
 * `size` always wins when present, whatever the shape or fallback state.
 * Without it, sizing is unchanged from before the field existed: circular
 * or no-bitmap floors at the touch target, a natural bitmap fills the row.
 */
internal fun imageDimension(
    circular: Boolean,
    hasBitmap: Boolean,
    size: Int?,
): ImageDimension =
    when {
        size != null -> ImageDimension.Fixed(size.dp)
        circular || !hasBitmap -> ImageDimension.TouchTarget
        else -> ImageDimension.NaturalWidth
    }
