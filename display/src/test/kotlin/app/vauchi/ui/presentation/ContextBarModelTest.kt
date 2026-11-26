// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core's context bar now draws inside the surface: back and the navigation
 * launcher can both lead the title row (back first), info and actions
 * trail it (info first), and primary is a full-width button under the
 * surface content (vauchi/private#534, retiring the bottom row from
 * #479). Which of Core's slots the title row and the footer draw is pure
 * enough to pin without composing.
 */
class ContextBarModelTest {
    @Test
    fun `back and the navigation launcher both lead, back first`() {
        assertEquals(
            listOf(ContextBarSlot.Back, ContextBarSlot.Navigation),
            ContextBarModel(bar(back = true, navigation = true)).leadingSlots,
        )
    }

    @Test
    fun `the navigation launcher leads alone when there is no back`() {
        assertEquals(listOf(ContextBarSlot.Navigation), ContextBarModel(bar(navigation = true)).leadingSlots)
    }

    @Test
    fun `the navigation launcher is left out while the navigation is on screen`() {
        assertEquals(
            listOf(ContextBarSlot.Back),
            ContextBarModel(bar(back = true, navigation = true), navigationShown = true).leadingSlots,
        )
        assertTrue(ContextBarModel(bar(navigation = true), navigationShown = true).leadingSlots.isEmpty())
    }

    @Test
    fun `no back and no navigation leaves the leading end empty`() {
        assertTrue(ContextBarModel(bar()).leadingSlots.isEmpty())
        assertTrue(ContextBarModel(null).leadingSlots.isEmpty())
    }

    @Test
    fun `the trailing end draws info before actions`() {
        assertEquals(
            listOf(ContextBarSlot.Info, ContextBarSlot.Secondary),
            ContextBarModel(bar(info = true, secondary = true)).trailingSlots,
        )
    }

    @Test
    fun `an absent trailing slot is left out rather than held open`() {
        assertEquals(listOf(ContextBarSlot.Info), ContextBarModel(bar(info = true)).trailingSlots)
        assertEquals(listOf(ContextBarSlot.Secondary), ContextBarModel(bar(secondary = true)).trailingSlots)
        assertTrue(ContextBarModel(bar()).trailingSlots.isEmpty())
        assertTrue(ContextBarModel(null).trailingSlots.isEmpty())
    }

    @Test
    fun `hasPrimary mirrors Core's primary slot`() {
        assertTrue(ContextBarModel(bar(primary = true)).hasPrimary)
        assertFalse(ContextBarModel(bar()).hasPrimary)
        assertFalse(ContextBarModel(null).hasPrimary)
    }

    private fun bar(
        back: Boolean = false,
        navigation: Boolean = false,
        primary: Boolean = false,
        secondary: Boolean = false,
        info: Boolean = false,
    ): ContextBar =
        ContextBar(
            back = if (back) action("back") else null,
            navigation = if (navigation) action("navigation") else null,
            primary = if (primary) action("primary") else null,
            secondary = if (secondary) action("secondary") else null,
            info = if (info) action("info") else null,
        )

    private fun action(interactionId: String): ActionSpec =
        ActionSpec(
            interactionId = interactionId,
            label = interactionId,
            accessibilityLabel = interactionId,
            iconToken = null,
            enabled = true,
            tone = ActionTone.Standard,
            shortcut = null,
        )
}
