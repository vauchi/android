// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.vauchi.R

/**
 * What a core icon token draws as: a Material glyph, or one of Vauchi's
 * own bundled pictograms. Both render through [painter], so a call site
 * tints either with `Icon(painter, tint = ...)` the same way.
 */
sealed interface IconSource {
    data class Vector(
        val image: ImageVector,
    ) : IconSource

    data class Drawable(
        @DrawableRes val resId: Int,
    ) : IconSource
}

@Composable
fun IconSource.painter(): Painter =
    when (this) {
        is IconSource.Vector -> rememberVectorPainter(image)
        is IconSource.Drawable -> painterResource(resId)
    }

private const val PICTOGRAM_PREFIX = "pictogram."

/**
 * Bundled pictograms keyed by drawable name. An explicit table rather
 * than `Resources.getIdentifier`: the direct `R.drawable` references keep
 * the resource shrinker from stripping the artwork, and a pictogram
 * missing here shows up at compile time. Mirrors the files under
 * `res/drawable/pictogram_*.xml`, copied from
 * `assets/pictograms/<group>/generated/android/`.
 */
private val bundledPictograms: Map<String, Int> =
    mapOf(
        "pictogram_exchange_glance" to R.drawable.pictogram_exchange_glance,
        "pictogram_exchange_hover" to R.drawable.pictogram_exchange_hover,
        "pictogram_exchange_bump" to R.drawable.pictogram_exchange_bump,
        "pictogram_exchange_shake" to R.drawable.pictogram_exchange_shake,
        "pictogram_exchange_magic" to R.drawable.pictogram_exchange_magic,
        "pictogram_exchange_tap_tap" to R.drawable.pictogram_exchange_tap_tap,
        "pictogram_exchange_tap_hover_shake" to R.drawable.pictogram_exchange_tap_hover_shake,
        "pictogram_exchange_link" to R.drawable.pictogram_exchange_link,
        "pictogram_exchange_cable" to R.drawable.pictogram_exchange_cable,
    )

/**
 * Resolves `pictogram.<group>.<name>` to the bundled drawable
 * `pictogram_<group>_<name>` by spelling alone, so a pictogram Core adds
 * needs only its asset here, never a per-token branch (ADR-066).
 */
@DrawableRes
fun pictogramDrawable(token: String): Int? = if (token.startsWith(PICTOGRAM_PREFIX)) bundledPictograms[token.replace('.', '_')] else null

/** [navigationIcon] widened to bundled pictograms; still total. */
fun navigationIconSource(token: String): IconSource =
    pictogramDrawable(token)?.let(IconSource::Drawable) ?: IconSource.Vector(navigationIcon(token))

/** [statusIcon] widened to bundled pictograms; still `null` when unknown. */
fun statusIconSource(token: String): IconSource? =
    pictogramDrawable(token)?.let(IconSource::Drawable) ?: statusIcon(token)?.let(IconSource::Vector)
