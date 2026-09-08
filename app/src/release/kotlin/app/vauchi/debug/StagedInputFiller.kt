// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.debug

import android.content.Context
import app.vauchi.ui.presentation.PresentationState

/**
 * Release-variant no-op stub for [StagedInputFiller].
 *
 * The real filler lives in `app/src/debug/` and is compiled only into
 * debug APKs. Its `MainActivity` call site is gated by
 * `BuildConfig.DEBUG`, so R8 tree-shakes this stub as unreachable; it
 * exists only to keep `MainActivity` compiling in both variants.
 *
 * A release build must never read an on-device file into an input
 * field — that is a test affordance, not a product one.
 *
 * Per principle "diagnostics should only be in test/debug build,
 * never in production" (2026-04-19-diagnostics-out-of-production-plan.md).
 */
object StagedInputFiller {
    const val STAGED_FILE_NAME: String = "staged-pick.txt"

    data class StagedFill(
        val surfaceId: String,
        val bindingId: String,
        val text: String,
    )

    fun pendingFill(
        context: Context,
        state: PresentationState,
    ): StagedFill? = null

    fun clearStaged(context: Context) {}
}
