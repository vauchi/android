// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `imageDimension` is the seam between Core's optional `size` and the
 * `Modifier` `PresentationNodeRenderer` draws an Image node with — pinned
 * here so the pre-`size` behaviour (touch-target floor for circular or
 * no-bitmap, full width for a natural bitmap) survives unchanged, and an
 * explicit `size` always wins once Core sends one.
 */
@RunWith(RobolectricTestRunner::class)
class ImageSizingTest {
    @Test
    fun `a circular avatar with no size still floors at the touch target`() {
        assertEquals(
            ImageDimension.TouchTarget,
            imageDimension(circular = true, hasBitmap = true, size = null),
        )
    }

    @Test
    fun `a natural bitmap with no size still fills the row`() {
        assertEquals(
            ImageDimension.NaturalWidth,
            imageDimension(circular = false, hasBitmap = true, size = null),
        )
    }

    @Test
    fun `no bitmap with no size still floors at the touch target`() {
        assertEquals(
            ImageDimension.TouchTarget,
            imageDimension(circular = false, hasBitmap = false, size = null),
        )
    }

    @Test
    fun `an explicit size wins over the natural-width bitmap default`() {
        assertEquals(
            ImageDimension.Fixed(88.dp),
            imageDimension(circular = false, hasBitmap = true, size = 88),
        )
    }

    @Test
    fun `an explicit size wins even for a circular avatar`() {
        assertEquals(
            ImageDimension.Fixed(88.dp),
            imageDimension(circular = true, hasBitmap = true, size = 88),
        )
    }

    @Test
    fun `an explicit size still sizes a text-only fallback`() {
        assertEquals(
            ImageDimension.Fixed(88.dp),
            imageDimension(circular = false, hasBitmap = false, size = 88),
        )
    }
}
