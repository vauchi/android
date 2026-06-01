// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for [exchangeExitDecision] — the latch that lets a native
 * exchange screen follow core off its screen. Regression guard for Bug 2
 * of `2026-05-30-exchange-screen-nav-visual-bugs`: the native multi-stage
 * screen lives in the Activity's local `Screen` enum (not the
 * `CoreScreenView` dispatch — `coreScreenIdToVariant` returns null for
 * `exchange_*`), so when core's own navigation (the Cancel action's
 * `navigate_back`) moved off it, nothing popped the local enum and the
 * screen looked frozen. The latch guards the entry race where the
 * composable mounts before core reaches its screen.
 */
class ExchangeScreenExitTest {
    private val own = "multi_stage_exchange"

    @Test
    fun `does not exit before core reaches the screen (entry race)`() {
        // Composable just mounted; core still on the previous screen.
        val d = exchangeExitDecision(entered = false, coreScreenId = "exchange", ownScreenId = own)
        assertFalse("must not exit before entering", d.shouldExit)
        assertFalse("not yet entered", d.entered)
    }

    @Test
    fun `latches entered when core reaches the screen`() {
        val d = exchangeExitDecision(entered = false, coreScreenId = own, ownScreenId = own)
        assertTrue("entered latches true", d.entered)
        assertFalse("no exit while on the screen", d.shouldExit)
    }

    @Test
    fun `stays entered and does not exit while still on the screen`() {
        val d = exchangeExitDecision(entered = true, coreScreenId = own, ownScreenId = own)
        assertTrue(d.entered)
        assertFalse(d.shouldExit)
    }

    @Test
    fun `exits once core navigates away after entering`() {
        // Cancel → core navigate_back → screen id is now the picker.
        val d = exchangeExitDecision(entered = true, coreScreenId = "exchange", ownScreenId = own)
        assertTrue("exit fires", d.shouldExit)
        assertTrue("entered stays latched", d.entered)
    }

    @Test
    fun `exits on null screen id after entering`() {
        val d = exchangeExitDecision(entered = true, coreScreenId = null, ownScreenId = own)
        assertTrue(d.shouldExit)
    }
}
