// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.vauchi.ui.presentation.PresentationColors

/** Material roles painted from the colours Core prepared for the active surface (vauchi/private#814). */
fun PresentationColors.toColorScheme(base: ColorScheme): ColorScheme {
    val surfaceColor = coreColor(surface)
    val textColor = coreColor(text)
    val accentColor = coreColor(accent)
    return base.copy(
        primary = accentColor,
        // Core sends no darker accent; halfway to the background keeps the
        // Exchange tab's unselected circle distinct from the selected one.
        primaryContainer = lerp(accentColor, coreColor(background), 0.5f),
        onPrimaryContainer = textColor,
        onPrimary = textOnAccent?.let(::coreColor) ?: base.onPrimary,
        background = coreColor(background),
        onBackground = textColor,
        surface = surfaceColor,
        onSurface = textColor,
        surfaceVariant = surfaceMuted?.let(::coreColor) ?: surfaceColor,
        surfaceContainerLowest = surfaceColor,
        surfaceContainerLow = surfaceColor,
        surfaceContainer = surfaceColor,
        surfaceContainerHigh = surfaceColor,
        surfaceContainerHighest = surfaceColor,
        surfaceBright = surfaceColor,
        surfaceDim = surfaceColor,
        secondaryContainer = surfaceColor,
        onSecondaryContainer = textColor,
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
    val scheme = colors.toColorScheme(MaterialTheme.colorScheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val darkIcons = scheme.background.wantsDarkSystemBarIcons()
            // The root pads for the button bar, so the window shows behind it.
            window.decorView.setBackgroundColor(scheme.background.toArgb())
            // Android greys the button bar for contrast; the icons already
            // follow the theme's background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalStatusColors provides colors.statusColors(), content = content)
    }
}

/** The bars are transparent over the background, so their icons follow its lightness. */
internal fun Color.wantsDarkSystemBarIcons(): Boolean = luminance() > 0.5f

/** Core sends `#rrggbb`; anything else paints transparent rather than crash the frame. */
internal fun coreColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return Color.Transparent
    return Color(0xFF000000 or rgb)
}
