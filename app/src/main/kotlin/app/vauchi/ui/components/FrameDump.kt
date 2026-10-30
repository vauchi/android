// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import java.io.File

/**
 * Writes every [everyNth] camera frame, as the decoder receives it, up to
 * [maxFrames]: the scanner's real-frame test corpus comes from these
 * (vauchi/private#931, #933).
 *
 * The frames show the exchange codes, which carry key material, so only a
 * debuggable build dumps, and only after `files/qr-dump/` is created with
 * `adb shell run-as`, which a release build refuses.
 */
class FrameDump(
    private val dir: File,
    private val everyNth: Int = 5,
    private val maxFrames: Int = 200,
) {
    private var offered = 0
    private var written = 0

    fun offer(
        luma: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        val index = offered++
        if (index % everyNth != 0 || written >= maxFrames) return
        File(dir, "f${index}_${width}x${height}_r$rotationDegrees.y").writeBytes(luma)
        written++
    }

    companion object {
        const val DUMP_FOLDER = "qr-dump"

        fun enabledIn(
            filesDir: File,
            debuggable: Boolean,
            runName: String,
        ): FrameDump? {
            val dumpFolder = File(filesDir, DUMP_FOLDER)
            if (!debuggable || !dumpFolder.isDirectory) return null
            val runFolder = File(dumpFolder, runName).apply { mkdirs() }
            return FrameDump(runFolder)
        }
    }
}
