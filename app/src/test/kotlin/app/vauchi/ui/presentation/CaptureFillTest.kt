// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On a fixed surface the node that holds the camera takes the height the
 * others leave, wherever Core nests it: a camera beside a column of
 * commands sits inside a row, not at the top level.
 *
 * Traces to: features/generic_presentation_protocol.feature
 */
class CaptureFillTest {
    private val a11y = AccessibilitySpec(label = "", description = null)

    private fun qr(capture: Boolean) =
        PresentationNode.Qr(
            id = "qr",
            payloads = if (capture) emptyList() else listOf("payload"),
            capture = capture,
            label = null,
            accessibility = a11y,
        )

    private fun group(
        horizontal: Boolean,
        vararg children: PresentationNode,
    ) = PresentationNode.Group(
        id = null,
        label = null,
        horizontal = horizontal,
        children = children.toList(),
        accessibility = a11y,
    )

    private val text = PresentationNode.Text(id = null, content = "Sending 1/3", style = TextRole.Body, accessibility = a11y)

    @Test
    fun `a camera, a row holding one, and a column nested in a row all hold the camera`() {
        assertEquals(true, qr(capture = true).holdsCamera)
        assertEquals(true, group(true, qr(capture = true), group(false, text)).holdsCamera)
        assertEquals(true, group(false, group(true, text, qr(capture = true))).holdsCamera)
    }

    @Test
    fun `a displayed code and a row without a camera do not`() {
        assertEquals(false, qr(capture = false).holdsCamera)
        assertEquals(false, group(true, qr(capture = false), text).holdsCamera)
        assertEquals(false, text.holdsCamera)
    }

    @Test
    fun `the camera takes the height left over and a displayed code gives way on a short screen`() {
        assertEquals(FixedSurfaceShare(weight = 1f, fill = true), qr(capture = true).fixedSurfaceShare)
        assertEquals(
            FixedSurfaceShare(weight = 1f, fill = true),
            group(true, qr(capture = true), group(false, text)).fixedSurfaceShare,
        )
        assertEquals(FixedSurfaceShare(weight = 4f, fill = false), qr(capture = false).fixedSurfaceShare)
    }

    @Test
    fun `other nodes keep their natural height on a fixed surface`() {
        assertEquals(null, text.fixedSurfaceShare)
        assertEquals(null, group(true, qr(capture = false), text).fixedSurfaceShare)
    }
}
