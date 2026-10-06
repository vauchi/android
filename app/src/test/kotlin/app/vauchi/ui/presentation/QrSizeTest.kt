// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Core may ask for a display code's square at `size: "compact"` on a short
 * window, so the camera under the code keeps room (vauchi/private#513).
 * Absent or unknown means the standard square.
 *
 * Traces to: features/generic_presentation_protocol.feature
 */
class QrSizeTest {
    private fun decodeQr(size: String?): PresentationNode.Qr {
        val json =
            """
            {"commands":[{"ReplaceSurface":{"surface":{
              "surface_id":"exchange",
              "revision":1,
              "title":"Glance",
              "subtitle":null,
              "accessibility_label":"Glance",
              "layout":"fixed",
              "tokens":{"spacing_small":8,"spacing_medium":16,"spacing_large":24,
                        "corner_radius":12,"minimum_target_size":48},
              "nodes":[{"Qr":{
                "id":"surface.1.own_qr",
                "payloads":["CODE"],
                "purpose":"display",
                "label":null,
                ${size?.let { "\"size\":\"$it\"," }.orEmpty()}
                "accessibility":{"label":"Show this to exchange","hint":null,"role":null}
              }}]
            }}}]}
            """.trimIndent()
        val command =
            PresentationProtocol.decodeEnvelope(json).commands.single()
                as PresentationCommand.ReplaceSurface
        return command.surface.nodes.single() as PresentationNode.Qr
    }

    @Test
    fun `a compact size is decoded and drawn smaller`() {
        val node = decodeQr("compact")

        assertEquals("compact", node.size)
        assertEquals(240, qrSquareMaxDp(node.size))
    }

    @Test
    fun `an absent or unknown size draws the standard square`() {
        assertNull(decodeQr(null).size)
        assertEquals(320, qrSquareMaxDp(null))
        assertEquals(320, qrSquareMaxDp("standard"))
        assertEquals(320, qrSquareMaxDp("huge"))
    }
}
