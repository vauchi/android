// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Core's `size` on an Image node is optional — present only where it
 * wants explicit sizing (e.g. the onboarding mark), absent everywhere it
 * leaves sizing to the shell (all avatars today). The parser must keep
 * decoding JSON that omits it, so an older-shaped payload still parses.
 */
class PresentationImageParsingTest {
    @Test
    fun `an explicit size decodes onto the node`() {
        val image = decodeImage(withSize = true)

        assertEquals(88, image.size)
    }

    @Test
    fun `a missing size decodes as null, unchanged from before the field existed`() {
        val image = decodeImage(withSize = false)

        assertNull(image.size)
    }

    private fun decodeImage(withSize: Boolean): PresentationNode.Image {
        val sizeField = if (withSize) """"size":88,""" else ""
        val command =
            PresentationProtocol
                .decodeEnvelope(
                    """
                    {"commands":[{"ReplaceSurface":{"surface":{
                      "surface_id":"main","revision":1,"title":"t","subtitle":null,
                      "accessibility_label":"t","layout":"scroll",
                      "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,"corner_radius":8,"minimum_target_size":44},
                      "nodes":[{"Image":{
                        "id":"vauchi_mark","data":null,"fallback_text":"Vauchi","shape":"natural",
                        "brightness":0.0,$sizeField"activation":null,
                        "accessibility":{"label":"Vauchi","description":null}
                      }}]}}}]}
                    """.trimIndent(),
                ).commands
                .single() as PresentationCommand.ReplaceSurface
        return command.surface.nodes.single() as PresentationNode.Image
    }
}
