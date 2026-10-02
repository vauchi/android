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

/** A code's side and top-left corner inside a square, in the square's units. */
internal data class QrFrame(
    val side: Float,
    val left: Float,
    val top: Float,
)

private const val FULL = 1000

/**
 * The frame to draw a code at inside a square of [squareSide]. No placement
 * is the full square. Core only sends placements inside the square; a value
 * outside it is pulled back in, so the code is never drawn past its node.
 */
internal fun qrFrame(
    placement: QrPlacement?,
    squareSide: Float,
): QrFrame {
    if (placement == null || placement.size <= 0) return QrFrame(squareSide, 0f, 0f)
    val size = placement.size.coerceAtMost(FULL)
    val room = FULL - size
    // Multiply before dividing: 650 × 300 / 1000 is exact, 650 × 0.3 is not.
    fun scaled(permille: Int): Float = permille * squareSide / FULL
    return QrFrame(
        side = scaled(size),
        left = scaled(placement.x.coerceIn(0, room)),
        top = scaled(placement.y.coerceIn(0, room)),
    )
}
