// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.PresentationQrErrorCorrection

class QrSymbol internal constructor(
    val side: Int,
    private val modules: BooleanArray,
) {
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = modules[y * side + x]

    override fun toString(): String = "QrSymbol"
}

object NativeQrEncoder {
    fun encode(
        data: String,
        level: PresentationQrErrorCorrection? = null,
    ): QrSymbol? = null
}
