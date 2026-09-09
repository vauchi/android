// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.debug

import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationState

/**
 * Release-variant no-op stub for [OnboardingWalker].
 *
 * The real walker lives in `app/src/debug/` and is compiled only into
 * debug APKs. Its `MainActivity` call site is gated by
 * `BuildConfig.DEBUG`, so R8 tree-shakes this stub as unreachable; it
 * exists only to keep `MainActivity` compiling in both variants.
 *
 * A release build must never complete onboarding on its own — that is
 * a test affordance, not a product one.
 */
object OnboardingWalker {
    const val SEED_DISPLAY_NAME: String = "Test User"

    data class Step(
        val event: PresentationEvent,
        val revision: ULong,
    )

    fun nextStep(
        state: PresentationState,
        displayName: String = SEED_DISPLAY_NAME,
    ): Step? = null
}
