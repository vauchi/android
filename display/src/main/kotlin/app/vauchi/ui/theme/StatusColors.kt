// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors sourced from the current vauchi theme's
 * success / warning / accent tokens. Material3's ColorScheme has no
 * slots for these, so they are exposed via their own CompositionLocal.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val info: Color,
)

val LocalStatusColors =
    staticCompositionLocalOf<StatusColors> {
        error("StatusColors not provided — wrap the composable in VauchiTheme")
    }
