// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core sends `minimum_target_size` per surface so every shell enforces the
 * same touch-target floor. `minimumTouchTarget` is the seam between that
 * wire value and the `Dp` a modifier consumes — pinned here so a hardcoded
 * 44/48 cannot silently replace it in a renderer again.
 */
@RunWith(RobolectricTestRunner::class)
class PresentationTokensLocalTest {
    private fun tokens(minimumTargetSize: Int) =
        PresentationTokens(
            spacingSmall = 4,
            spacingMedium = 8,
            spacingLarge = 16,
            cornerRadius = 8,
            minimumTargetSize = minimumTargetSize,
        )

    @Test
    fun `the touch target follows the surface's own token, not a hardcoded floor`() {
        assertEquals(44.dp, minimumTouchTarget(tokens(minimumTargetSize = 44)))
        assertEquals(56.dp, minimumTouchTarget(tokens(minimumTargetSize = 56)))
    }

    @Test
    fun `the default tokens match the shipped design floor`() {
        assertEquals(48, DefaultPresentationTokens.minimumTargetSize)
    }
}
