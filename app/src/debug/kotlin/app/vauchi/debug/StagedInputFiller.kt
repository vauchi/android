// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.debug

import android.content.Context
import android.util.Log
import app.vauchi.ui.presentation.PresentationNode
import app.vauchi.ui.presentation.PresentationState

/**
 * Fills the active surface's first text input from a file staged by
 * `just dt-stage-backup`, for payloads no host-side input channel can
 * carry.
 *
 * The 10k-contact restore in problem record
 * 2026-06-11-restore-runs-without-progress-feedback pastes ~18 MB.
 * `adb shell input text` cannot carry that, and adb cannot set the
 * Android clipboard, so the record's device criterion had no
 * automatable path. The blob arrives instead in the app's own
 * external-files directory, which adb writes without a runtime
 * permission and this reads without one.
 *
 * The binding id is taken from the surface Core rendered, never
 * constructed here — ADR-066 keeps opaque ids Core-minted, and a shell
 * that invents them would pass this test while the real one broke.
 */
object StagedInputFiller {
    const val STAGED_FILE_NAME: String = "staged-pick.txt"
    private const val TAG = "Vauchi"

    /** A staged blob and the Core-minted binding it should fill. */
    data class StagedFill(
        val surfaceId: String,
        val bindingId: String,
        val text: String,
    )

    /**
     * Returns what to fill, or null when nothing is staged or the active
     * surface has no enabled input. Callers dispatch the event themselves
     * so this stays free of a ViewModel handle.
     */
    fun pendingFill(
        context: Context,
        state: PresentationState,
    ): StagedFill? {
        val staged = java.io.File(context.getExternalFilesDir(null), STAGED_FILE_NAME)
        if (!staged.isFile) {
            Log.i(TAG, "StagedInputFiller: nothing staged")
            return null
        }

        val surfaceId = state.activeSurfaceId ?: return null
        val input =
            state.surfaces[surfaceId]
                ?.nodes
                ?.filterIsInstance<PresentationNode.Input>()
                ?.firstOrNull { it.enabled }
        if (input == null) {
            Log.i(TAG, "StagedInputFiller: active surface has no enabled input")
            return null
        }

        val text = staged.readText()
        Log.i(TAG, "StagedInputFiller: filling ${input.bindingId} with ${text.length} chars")
        return StagedFill(surfaceId, input.bindingId, text)
    }

    /** Deletes the staged file so a fixture cannot leak into a later run. */
    fun clearStaged(context: Context) {
        java.io.File(context.getExternalFilesDir(null), STAGED_FILE_NAME).delete()
    }
}
