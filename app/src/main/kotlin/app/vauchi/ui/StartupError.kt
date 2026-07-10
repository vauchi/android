// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.biometric.BiometricPrompt

/**
 * Why app startup cannot proceed. Typed at the site that still holds
 * the cause (exception type or [BiometricPrompt] error code) so no
 * screen re-derives it by substring-matching display text — resolves
 * finding A1 in `2026-07-06-mobile-domain-shell-violations`.
 */
enum class StartupErrorKind {
    /** No secure lock screen / device credential is configured. */
    DeviceNotSecure,

    /** The user dismissed or cancelled the unlock prompt. */
    AuthCancelled,

    /** Anything else; rendered with its detail text. */
    Other,
}

/** Classifies a [BiometricPrompt] error code at the callback boundary. */
fun startupErrorKindFor(biometricErrorCode: Int): StartupErrorKind =
    when (biometricErrorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> StartupErrorKind.AuthCancelled

        else -> StartupErrorKind.Other
    }
