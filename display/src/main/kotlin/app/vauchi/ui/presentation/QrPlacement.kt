// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

/**
 * Where Core asks a display code to be drawn inside its square: the code's
 * side and the offset of its top-left corner, in permille of the square's
 * side.
 */
data class QrPlacement(
    val size: Int,
    val x: Int,
    val y: Int,
)
