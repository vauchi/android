// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context bar sits directly above the tab bar. On the phone its
 * launchers were bare icons nobody could name and its navigation launcher
 * opened the same five destinations as the tab bar under it
 * (vauchi/private#479). Which of Core's four slots is drawn is pure enough
 * to pin without composing.
 */
class ContextBarModelTest {
    @Test
    fun `slots keep the order Core sends them in`() {
        val model = ContextBarModel(bar(back = true, navigation = true, primary = true, secondary = true))

        assertEquals(
            listOf(ContextBarSlot.Back, ContextBarSlot.Navigation, ContextBarSlot.Primary, ContextBarSlot.Secondary),
            model.slots,
        )
    }

    @Test
    fun `an absent slot is left out rather than held open`() {
        assertEquals(
            listOf(ContextBarSlot.Primary, ContextBarSlot.Secondary),
            ContextBarModel(bar(primary = true, secondary = true)).slots,
        )
        assertEquals(listOf(ContextBarSlot.Back), ContextBarModel(bar(back = true)).slots)
    }

    @Test
    fun `no bar and a bar with no actions draw nothing`() {
        assertFalse(ContextBarModel(null).isVisible)
        assertFalse(ContextBarModel(bar()).isVisible)
        assertTrue(ContextBarModel(bar(primary = true)).isVisible)
    }

    @Test
    fun `the navigation launcher is left out while the navigation is on screen`() {
        val full = bar(back = true, navigation = true, primary = true, secondary = true)

        assertEquals(
            listOf(ContextBarSlot.Back, ContextBarSlot.Primary, ContextBarSlot.Secondary),
            ContextBarModel(full, navigationShown = true).slots,
        )
    }

    @Test
    fun `launchers show their label and back does not`() {
        assertTrue(ContextBarSlot.Navigation.showsLabel)
        assertTrue(ContextBarSlot.Secondary.showsLabel)
        assertFalse(ContextBarSlot.Back.showsLabel)
    }

    private fun bar(
        back: Boolean = false,
        navigation: Boolean = false,
        primary: Boolean = false,
        secondary: Boolean = false,
    ): ContextBar =
        ContextBar(
            back = if (back) action("back") else null,
            navigation = if (navigation) action("navigation") else null,
            primary = if (primary) action("primary") else null,
            secondary = if (secondary) action("secondary") else null,
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
