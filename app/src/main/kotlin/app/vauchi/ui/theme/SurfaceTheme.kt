// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import app.vauchi.ui.presentation.PresentationColors

/** Material roles painted from the colours Core prepared for the active surface (vauchi/private#814). */
fun PresentationColors.toColorScheme(base: ColorScheme): ColorScheme {
    val surfaceColor = coreColor(surface)
    val textColor = coreColor(text)
    return base.copy(
        primary = coreColor(accent),
        onPrimary = textOnAccent?.let(::coreColor) ?: base.onPrimary,
        background = coreColor(background),
        onBackground = textColor,
        surface = surfaceColor,
        onSurface = textColor,
        surfaceVariant = surfaceMuted?.let(::coreColor) ?: surfaceColor,
        onSurfaceVariant = coreColor(textMuted),
        outline = coreColor(border),
        error = coreColor(error),
    )
}

fun PresentationColors.statusColors(): StatusColors =
    StatusColors(
        success = coreColor(success),
        warning = coreColor(warning),
        info = coreColor(accent),
    )

/** Paints [content] with Core's colours, or leaves the enclosing theme when Core sent none. */
@Composable
fun SurfaceTheme(
    colors: PresentationColors?,
    content: @Composable () -> Unit,
) {
    if (colors == null) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = colors.toColorScheme(MaterialTheme.colorScheme),
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalStatusColors provides colors.statusColors(), content = content)
    }
}

/** Core sends `#rrggbb`; anything else paints transparent rather than crash the frame. */
internal fun coreColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return Color.Transparent
    return Color(0xFF000000 or rgb)
}
