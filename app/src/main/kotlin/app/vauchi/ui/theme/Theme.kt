// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.vauchi.util.ThemeManager
import app.vauchi.util.hexToColor
import uniffi.vauchi_platform.MobileTheme

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
    theme: MobileTheme?,
    isDark: Boolean,
): ColorScheme {
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

private fun buildStatusColors(theme: MobileTheme?): StatusColors {
    if (theme == null) {
        return StatusColors(
            success = Color(0xFF2E7D32),
            warning = Color(0xFFF9A825),
            info = Color(0xFF1976D2),
        )
    }
    val c = theme.colors
    return StatusColors(
        success = hexToColor(c.success),
        warning = hexToColor(c.warning),
        info = hexToColor(c.accent),
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
    // Manager state is exposed as `StateFlow` (not `mutableStateOf`)
    // because the manager is a process-wide singleton — `mutableStateOf`
    // bound to a previous Compose runtime's snapshot system blew up
    // on cold-start with "Reading a state that was created after the
    // snapshot was taken" once the runtime was recreated (config
    // change, force-stop+relaunch). `collectAsState` decouples the
    // observation from any specific runtime.
    val theme by themeManager.currentTheme.collectAsState()

    // `applySelectedTheme` writes the `MutableStateFlow`s. Wrapped in
    // `SideEffect` so the write runs after composition commits; the
    // next recomposition sees the new `theme` value. First-frame
    // flash to default colors is acceptable; cold-start launch
    // crash is not.
    SideEffect {
        themeManager.applySelectedTheme(darkTheme)
    }

    val colorScheme = buildColorScheme(theme, darkTheme)
    val statusColors = buildStatusColors(theme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        CompositionLocalProvider(LocalStatusColors provides statusColors) {
            content()
        }
    }
}
