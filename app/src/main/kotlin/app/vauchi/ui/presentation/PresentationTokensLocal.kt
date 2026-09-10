// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fallback for the loading frame (no surface has arrived yet) and for
 * composables under test that render a primitive in isolation, without a
 * surrounding [PresentationSurface]. Mirrors themes/tokens.json's shipped
 * values, so an unwired render still measures a real touch target instead
 * of an arbitrary one.
 */
internal val DefaultPresentationTokens =
    PresentationTokens(
        spacingSmall = 4,
        spacingMedium = 8,
        spacingLarge = 16,
        cornerRadius = 8,
        minimumTargetSize = 48,
    )

/**
 * The active surface's design tokens, threaded through composition so
 * primitives below [PresentationSurface] read the touch-target floor,
 * spacing and corner radius Core sends without each taking a `tokens`
 * parameter.
 */
internal val LocalPresentationTokens = staticCompositionLocalOf { DefaultPresentationTokens }

internal fun minimumTouchTarget(tokens: PresentationTokens): Dp = tokens.minimumTargetSize.dp
