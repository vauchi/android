// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HexToColorTest {

    private fun assertColorComponents(
        expectedRed: Float,
        expectedGreen: Float,
        expectedBlue: Float,
        expectedAlpha: Float,
        actual: Color,
        delta: Float = 0.01f
    ) {
        assertEquals("red", expectedRed, actual.red, delta)
        assertEquals("green", expectedGreen, actual.green, delta)
        assertEquals("blue", expectedBlue, actual.blue, delta)
        assertEquals("alpha", expectedAlpha, actual.alpha, delta)
    }

    @Test
    fun `hexToColor parses red`() {
        val color = hexToColor("#FF0000")
        assertColorComponents(1f, 0f, 0f, 1f, color)
    }

    @Test
    fun `hexToColor parses green without hash prefix`() {
        val color = hexToColor("00FF00")
        assertColorComponents(0f, 1f, 0f, 1f, color)
    }

    @Test
    fun `hexToColor parses blue`() {
        val color = hexToColor("#0000FF")
        assertColorComponents(0f, 0f, 1f, 1f, color)
    }

    @Test
    fun `hexToColor parses black`() {
        val color = hexToColor("#000000")
        assertColorComponents(0f, 0f, 0f, 1f, color)
    }

    @Test
    fun `hexToColor parses white`() {
        val color = hexToColor("#FFFFFF")
        assertColorComponents(1f, 1f, 1f, 1f, color)
    }

    @Test
    fun `hexToColor returns Transparent for invalid hex`() {
        val color = hexToColor("not-a-color")
        assertEquals(Color.Transparent, color)
    }

    @Test
    fun `hexToColor returns Transparent for empty string`() {
        val color = hexToColor("")
        assertEquals(Color.Transparent, color)
    }

    @Test
    fun `hexToColor handles 8-digit ARGB hex`() {
        val color = hexToColor("#FFFF0000")
        assertColorComponents(1f, 0f, 0f, 1f, color)
    }

    @Test
    fun `hexToColor handles semi-transparent color`() {
        val color = hexToColor("#80FF0000")
        assertColorComponents(1f, 0f, 0f, 0.5f, color, delta = 0.02f)
    }

    @Test
    fun `hexToColor is case insensitive`() {
        val upper = hexToColor("#FF0000")
        val lower = hexToColor("#ff0000")
        assertColorComponents(upper.red, upper.green, upper.blue, upper.alpha, lower)
    }
}
