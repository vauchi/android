// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Core pins a few actions under a surface's content for a decision that
 * must not scroll away, and only for a shell that says it keeps them in
 * view (vauchi/private#1304). Both are optional on the wire.
 */
class PinnedActionsTest {
    private fun action(
        index: Int,
        label: String,
    ) = """
        {"interaction_id":"surface.7.context.presentation.pinned.$index","label":"$label",
         "accessibility_label":"$label","icon_token":null,"enabled":true,"shortcut":null}
        """.trimIndent()

    private fun decodeBar(barJson: String): ContextBar {
        val json =
            """
            {"commands":[{"SetContextBar":{"surface_id":"main","revision":1,"bar":$barJson}}]}
            """.trimIndent()
        val command =
            PresentationProtocol.decodeEnvelope(json).commands.single()
                as PresentationCommand.SetContextBar
        return command.bar
    }

    // @internal
    @Test
    fun `a bar decodes its pinned actions in Core's order`() {
        val yes = action(0, "Start a new connection")
        val no = action(1, "Leave it")

        val bar =
            decodeBar(
                """{"back":null,"navigation":null,"primary":null,"secondary":null,"pinned":[$yes,$no]}""",
            )

        assertEquals(listOf("Start a new connection", "Leave it"), bar.pinned.map { it.label })
        assertEquals(
            listOf(
                "surface.7.context.presentation.pinned.0",
                "surface.7.context.presentation.pinned.1",
            ),
            bar.pinned.map { it.interactionId },
        )
    }

    // @internal
    @Test
    fun `a bar from a Core without the slot has none pinned`() {
        val bar =
            decodeBar(
                """{"back":null,"navigation":null,"primary":${action(0, "Done")},"secondary":null}""",
            )

        assertEquals(emptyList<ActionSpec>(), bar.pinned)
        assertEquals("Done", bar.primary?.label)
    }

    // @internal
    @Test
    fun `every environment report says this shell keeps pinned actions in view`() {
        val event =
            PresentationEvent.environmentChanged(
                width = 393,
                height = 760,
                inputModes = listOf(InputMode.Touch),
                reducedMotion = false,
            )

        val environment =
            Json
                .parseToJsonElement(event.toJson())
                .jsonObject
                .getValue("PresentationEnvironmentChanged")
                .jsonObject

        assertEquals("true", environment.getValue("pinned_actions").toString())
    }
}
