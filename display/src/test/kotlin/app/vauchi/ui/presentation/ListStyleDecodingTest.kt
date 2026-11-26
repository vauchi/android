// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core sends a list's `style` only when it is not an ordinary list, and
 * as an open string: "buttons" draws the rows as native buttons, while an
 * absent or unrecognised value must still draw rows rather than fail the
 * surface.
 *
 * Traces to: features/generic_presentation_protocol.feature
 */
class ListStyleDecodingTest {
    private fun decodeList(style: String?): PresentationNode.ListNode {
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
              "nodes":[{"List":{
                "id":"surface.1.exchange_actions",
                "label":null,
                "rows":[{
                  "title":"Cancel",
                  "subtitle":null,
                  "detail":null,
                  "icon_token":null,
                  "image_data":null,
                  "fallback_text":null,
                  "selected":false,
                  "enabled":true,
                  "activation":{
                    "interaction_id":"surface.1.action.0",
                    "label":"Cancel",
                    "accessibility_label":"Cancel",
                    "icon_token":null,
                    "enabled":true,
                    "shortcut":null
                  },
                  "secondary_actions":[],
                  "controls":[],
                  "accessibility":{"label":"Cancel","hint":null,"role":null}
                }],
                "searchable":false,
                "paging":null,
                ${style?.let { "\"style\":\"$it\"," }.orEmpty()}
                "accessibility":{"label":"","hint":null,"role":null}
              }}]
            }}}]}
            """.trimIndent()
        val command =
            PresentationProtocol.decodeEnvelope(json).commands.single()
                as PresentationCommand.ReplaceSurface
        return command.surface.nodes.single() as PresentationNode.ListNode
    }

    @Test
    fun `buttons style draws the rows as buttons`() {
        assertEquals(true, decodeList("buttons").drawsButtons)
    }

    @Test
    fun `an absent style draws an ordinary list`() {
        assertEquals(false, decodeList(null).drawsButtons)
    }

    @Test
    fun `an unrecognised style falls back to an ordinary list`() {
        assertEquals(false, decodeList("carousel").drawsButtons)
    }
}
