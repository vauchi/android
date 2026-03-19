// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.vauchi.util.ThemeManager
import app.vauchi.util.hexToColor

/**
 * Build a Material3 [ColorScheme] from the current [ThemeManager] theme.
 *
 * Maps vauchi semantic tokens to Material3 roles:
 * - bg-primary    -> background
 * - bg-secondary  -> surface
 * - bg-tertiary   -> surfaceVariant
 * - text-primary  -> onBackground, onSurface
 * - text-secondary -> onSurfaceVariant
 * - accent        -> primary
 * - accent-dark   -> primaryContainer
 * - error         -> error
 * - border        -> outline
 */
private fun buildColorScheme(
    themeManager: ThemeManager,
    isDark: Boolean,
): ColorScheme {
    val theme = themeManager.currentTheme
    if (theme == null) {
        return if (isDark) darkColorScheme() else lightColorScheme()
    }
    val c = theme.colors
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = hexToColor(c.accent),
        primaryContainer = hexToColor(c.accentDark),
        background = hexToColor(c.bgPrimary),
        surface = hexToColor(c.bgSecondary),
        surfaceVariant = hexToColor(c.bgTertiary),
        onBackground = hexToColor(c.textPrimary),
        onSurface = hexToColor(c.textPrimary),
        onSurfaceVariant = hexToColor(c.textSecondary),
        error = hexToColor(c.error),
        outline = hexToColor(c.border),
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun VauchiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val themeManager = ThemeManager.getInstance(context)
    themeManager.applySelectedTheme(darkTheme)

    val colorScheme = buildColorScheme(themeManager, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
