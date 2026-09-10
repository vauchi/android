// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.vauchi.ui.theme.StatusColors
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `toneColors` is the seam that keeps a "serious" action from ever reading
 * as "destructive": the two must resolve to visibly different emphasis and
 * colour even though both are drawn from the same three-value tone.
 */
@RunWith(RobolectricTestRunner::class)
class ActionToneStyleTest {
    private val colorScheme =
        lightColorScheme(
            primary = Color(0xFF11467B),
            error = Color(0xFFB3261E),
        )
    private val statusColors =
        StatusColors(
            success = Color(0xFF2E7D32),
            warning = Color(0xFFF9A825),
            info = Color(0xFF1976D2),
        )

    @Test
    fun `standard tone is filled in the theme primary colour`() {
        val style = toneColors(ActionTone.Standard, colorScheme, statusColors)

        assertEquals(ActionToneEmphasis.Filled, style.emphasis)
        assertEquals(colorScheme.primary, style.accent)
    }

    @Test
    fun `destructive tone is filled in the theme error colour`() {
        val style = toneColors(ActionTone.Destructive, colorScheme, statusColors)

        assertEquals(ActionToneEmphasis.Filled, style.emphasis)
        assertEquals(colorScheme.error, style.accent)
    }

    @Test
    fun `serious tone is outlined in the warning colour, never filled or error red`() {
        val style = toneColors(ActionTone.Serious, colorScheme, statusColors)

        assertEquals(ActionToneEmphasis.Outlined, style.emphasis)
        assertEquals(statusColors.warning, style.accent)
        assertNotEquals(ActionToneEmphasis.Filled, style.emphasis)
        assertNotEquals(colorScheme.error, style.accent)
    }
}
