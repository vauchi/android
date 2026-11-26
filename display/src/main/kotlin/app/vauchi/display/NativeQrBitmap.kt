// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.graphics.Bitmap
import android.graphics.Color
import app.vauchi.contract.PresentationQrErrorCorrection

object NativeQrBitmap {
    private const val MODULE_PIXELS = 4

    fun encode(
        data: String,
        level: PresentationQrErrorCorrection? = null,
    ): Bitmap? {
        val symbol = NativeQrEncoder.encode(data, level) ?: return null
        val side = symbol.side * MODULE_PIXELS
        val pixels =
            IntArray(side * side) { index ->
                if (symbol[index % side / MODULE_PIXELS, index / side / MODULE_PIXELS]) Color.BLACK else Color.WHITE
            }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }
}
