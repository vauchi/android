// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.os.Build
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Core's `Command::Celebrate` names a haptic intent (`success`, `light`,
 * `none`); the shell maps it to the platform's feedback constant
 * (vauchi/private#276: Android collected the request but never fired one).
 */
class CelebrationHapticsTest {
    @Test
    fun `success is the platform confirm haptic where it exists`() {
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            celebrationHapticFeedback("success", Build.VERSION_CODES.R),
        )
    }

    @Test
    fun `success falls back to a long press before Android 11`() {
        assertEquals(
            HapticFeedbackConstants.LONG_PRESS,
            celebrationHapticFeedback("success", Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `light is a single clock tick`() {
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            celebrationHapticFeedback("light", Build.VERSION_CODES.R),
        )
    }

    @Test
    fun `none and an unknown intent fire nothing`() {
        assertNull(celebrationHapticFeedback("none", Build.VERSION_CODES.R))
        assertNull(celebrationHapticFeedback("rumble", Build.VERSION_CODES.R))
        assertNull(celebrationHapticFeedback("", Build.VERSION_CODES.R))
    }
}
