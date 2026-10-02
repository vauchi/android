// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import uniffi.vauchi_platform.MobileQrEccLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core may send a display code's `placement`: its side and the offset of
 * its top-left corner, in permille of the node's square. Absent means the
 * code fills the square. The shell only draws where it is told, and never
 * outside its own square.
 *
 * Traces to: features/generic_presentation_protocol.feature
 */
class QrPlacementTest {
    private fun decodeQr(
        placement: String?,
        errorCorrection: String? = null,
    ): PresentationNode.Qr {
        val json =
            """
            {"commands":[{"ReplaceSurface":{"surface":{
              "surface_id":"exchange",
              "revision":1,
              "title":"Exchange",
              "subtitle":null,
              "accessibility_label":"Exchange",
              "layout":"fixed",
              "tokens":{"spacing_small":8,"spacing_medium":16,"spacing_large":24,
                        "corner_radius":12,"minimum_target_size":48},
              "nodes":[{"Qr":{
                "id":"surface.1.own_qr",
                "payloads":["FRAME"],
                "purpose":"display",
                "label":null,
                ${placement?.let { "\"placement\":$it," }.orEmpty()}
                ${errorCorrection?.let { "\"error_correction\":\"$it\"," }.orEmpty()}
                "accessibility":{"label":"Your code","hint":null,"role":null}
              }}]
            }}}]}
            """.trimIndent()
        val command =
            PresentationProtocol.decodeEnvelope(json).commands.single()
                as PresentationCommand.ReplaceSurface
        return command.surface.nodes.single() as PresentationNode.Qr
    }

    @Test
    fun `a placement is decoded in permille`() {
        assertEquals(
            QrPlacement(size = 650, x = 350, y = 175),
            decodeQr("""{"size":650,"x":350,"y":175}""").placement,
        )
    }

    @Test
    fun `an absent placement leaves the code filling its square`() {
        assertEquals(null, decodeQr(null).placement)
    }

    @Test
    fun `a full square draws the code edge to edge`() {
        assertEquals(QrFrame(side = 300f, left = 0f, top = 0f), qrFrame(null, 300f))
    }

    @Test
    fun `a placed code is scaled and offset within the square`() {
        assertEquals(
            QrFrame(side = 195f, left = 105f, top = 52.5f),
            qrFrame(QrPlacement(size = 650, x = 350, y = 175), 300f),
        )
        assertEquals(
            QrFrame(side = 240f, left = 60f, top = 0f),
            qrFrame(QrPlacement(size = 800, x = 200, y = 0), 300f),
        )
    }

    @Test
    fun `a placement reaching outside the square is pulled back inside`() {
        // Core never sends these; a shell still must not draw past its node.
        assertEquals(
            QrFrame(side = 240f, left = 60f, top = 60f),
            qrFrame(QrPlacement(size = 800, x = 900, y = 5000), 300f),
        )
        assertEquals(
            QrFrame(side = 300f, left = 0f, top = 0f),
            qrFrame(QrPlacement(size = 4000, x = 10, y = 10), 300f),
        )
        assertEquals(
            QrFrame(side = 150f, left = 0f, top = 0f),
            qrFrame(QrPlacement(size = 500, x = -20, y = -1), 300f),
        )
    }

    @Test
    fun `a nonsensical size falls back to the full square`() {
        assertEquals(
            QrFrame(side = 300f, left = 0f, top = 0f),
            qrFrame(QrPlacement(size = 0, x = 0, y = 0), 300f),
        )
        assertEquals(
            QrFrame(side = 300f, left = 0f, top = 0f),
            qrFrame(QrPlacement(size = -5, x = 0, y = 0), 300f),
        )
    }

    @Test
    fun `a low error-correction level is decoded and drawn as such`() {
        assertEquals(
            MobileQrEccLevel.LOW,
            qrEccLevel(decodeQr(null, errorCorrection = "low").errorCorrection),
        )
    }

    @Test
    fun `an absent or unrecognised level draws at medium`() {
        assertEquals(null, decodeQr(null).errorCorrection)
        assertEquals(MobileQrEccLevel.MEDIUM, qrEccLevel(null))
        assertEquals(MobileQrEccLevel.MEDIUM, qrEccLevel("medium"))
        assertEquals(MobileQrEccLevel.MEDIUM, qrEccLevel("ultra"))
    }
}
