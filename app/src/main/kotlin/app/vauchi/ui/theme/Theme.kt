// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * The platform's light or dark defaults, for screens Core does not
 * present. Core-presented surfaces repaint with the active theme's
 * colours through [SurfaceTheme] (vauchi/private#814).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VauchiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        typography = Typography,
    ) {
        CompositionLocalProvider(LocalStatusColors provides DefaultStatusColors) {
            content()
        }
    }
}

private val DefaultStatusColors =
    StatusColors(
        success = Color(0xFF2E7D32),
        warning = Color(0xFFF9A825),
        info = Color(0xFF1976D2),
    )
