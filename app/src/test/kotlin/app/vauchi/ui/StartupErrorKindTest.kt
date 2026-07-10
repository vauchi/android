// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the typed classification that replaces `ErrorScreen`'s substring
 * matching on English error text (A1 in
 * `2026-07-06-mobile-domain-shell-violations`). The prompt itself is
 * OS-tested (CC-23); this tests the pure decision: which error codes
 * count as "the user cancelled" versus a real failure.
 */
class StartupErrorKindTest {
    @Test
    fun user_cancel_is_classified_as_cancellation() {
        assertEquals(
            StartupErrorKind.AuthCancelled,
            startupErrorKindFor(BiometricPrompt.ERROR_USER_CANCELED),
        )
    }

    @Test
    fun negative_button_is_classified_as_cancellation() {
        assertEquals(
            StartupErrorKind.AuthCancelled,
            startupErrorKindFor(BiometricPrompt.ERROR_NEGATIVE_BUTTON),
        )
    }

    @Test
    fun system_cancel_is_classified_as_cancellation() {
        assertEquals(
            StartupErrorKind.AuthCancelled,
            startupErrorKindFor(BiometricPrompt.ERROR_CANCELED),
        )
    }

    @Test
    fun lockout_is_a_real_failure_not_a_cancellation() {
        assertEquals(
            StartupErrorKind.Other,
            startupErrorKindFor(BiometricPrompt.ERROR_LOCKOUT),
        )
    }

    @Test
    fun hardware_unavailable_is_a_real_failure_not_a_cancellation() {
        assertEquals(
            StartupErrorKind.Other,
            startupErrorKindFor(BiometricPrompt.ERROR_HW_UNAVAILABLE),
        )
    }
}
