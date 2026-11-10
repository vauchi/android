// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Core's `Command::Celebrate` names an animation intent (`cards_meet`,
 * `none`); the shell plays its one-beat flash only for a named animation
 * and only when the OS allows motion (vauchi/private#276).
 */
class CelebrationAnimationTest {
    @Test
    fun `cards meet plays when motion is allowed`() {
        assertEquals(true, celebrationAnimationPlays("cards_meet", reducedMotion = false))
    }

    @Test
    fun `cards meet stays still under reduce motion`() {
        assertEquals(false, celebrationAnimationPlays("cards_meet", reducedMotion = true))
    }

    @Test
    fun `none and unknown intents play nothing`() {
        assertEquals(false, celebrationAnimationPlays("none", reducedMotion = false))
        assertEquals(false, celebrationAnimationPlays("fireworks", reducedMotion = false))
    }

    @Test
    fun `the beat is one moment, not a scene`() {
        assertEquals(600L, CELEBRATION_BEAT_MILLIS)
    }
}
