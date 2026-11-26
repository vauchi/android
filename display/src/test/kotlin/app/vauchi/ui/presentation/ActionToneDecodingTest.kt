// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core sends `tone` as an open string, so the shell must decode every
 * value it recognises and never let an unrecognised or absent one crash
 * the surface — it falls back to [ActionTone.Standard] instead.
 *
 * Traces to: features/accessibility.feature
 */
class ActionToneDecodingTest {
    private fun decodeTone(tone: String?): ActionTone {
        val json =
            """
            {"commands":[{"SetContextBar":{
              "surface_id":"main",
              "revision":1,
              "bar":{
                "back":null,
                "navigation":null,
                "primary":{
                  "interaction_id":"go",
                  "label":"Go",
                  "accessibility_label":"Go",
                  "icon_token":null,
                  "enabled":true,
                  ${tone?.let { "\"tone\":\"$it\"," }.orEmpty()}
                  "shortcut":null
                },
                "secondary":null
              }
            }}]}
            """.trimIndent()
        val command =
            PresentationProtocol.decodeEnvelope(json).commands.single()
                as PresentationCommand.SetContextBar
        return command.bar.primary!!.tone
    }

    @Test
    fun `destructive tone decodes to the destructive enum value`() {
        assertEquals(ActionTone.Destructive, decodeTone("destructive"))
    }

    @Test
    fun `serious tone decodes to the serious enum value`() {
        assertEquals(ActionTone.Serious, decodeTone("serious"))
    }

    @Test
    fun `an unrecognised tone string falls back to standard`() {
        assertEquals(ActionTone.Standard, decodeTone("catastrophic"))
    }

    @Test
    fun `a missing tone falls back to standard`() {
        assertEquals(ActionTone.Standard, decodeTone(null))
    }
}
