// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core explains a surface with an `information` overlay that carries a
 * body and no items, announced through the context bar's `info` slot
 * (vauchi/private#479). Both are optional on the wire, so a batch from an
 * older Core decodes as before.
 */
class InformationOverlayDecodingTest {
    private val infoAction =
        """
        {"interaction_id":"presentation.info","label":"Info",
         "accessibility_label":"About this screen","icon_token":null,
         "enabled":true,"shortcut":null}
        """.trimIndent()

    @Test
    fun `an information overlay decodes its kind and body`() {
        val overlay =
            PresentationProtocol.decodeOverlay(
                """
                {"kind":"information","title":"Contacts","items":[],
                 "body":"Here are the people you have exchanged cards with."}
                """.trimIndent(),
            )

        assertEquals(OverlayKind.Information, overlay.kind)
        assertEquals("Contacts", overlay.title)
        assertEquals("Here are the people you have exchanged cards with.", overlay.body)
        assertTrue(overlay.items.isEmpty())
    }

    @Test
    fun `an overlay decodes Core's label for its Close`() {
        val overlay =
            PresentationProtocol.decodeOverlay(
                """
                {"kind":"information","title":"Contacts","items":[],
                 "body":"Text.","close_label":"Close"}
                """.trimIndent(),
            )

        assertEquals("Close", overlay.closeLabel)
    }

    @Test
    fun `an overlay from an older Core has no Close label`() {
        val overlay =
            PresentationProtocol.decodeOverlay(
                """{"kind":"action_menu","title":"Actions","items":[]}""",
            )

        assertNull(overlay.closeLabel)
    }

    @Test
    fun `an overlay without a body still decodes`() {
        val overlay =
            PresentationProtocol.decodeOverlay(
                """{"kind":"action_menu","title":"Actions","items":[]}""",
            )

        assertEquals(OverlayKind.ActionMenu, overlay.kind)
        assertNull(overlay.body)
    }

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

    @Test
    fun `a context bar decodes the info slot`() {
        val bar =
            decodeBar(
                """{"back":null,"navigation":null,"primary":null,"secondary":null,"info":$infoAction}""",
            )

        assertEquals("Info", bar.info?.label)
        assertEquals("About this screen", bar.info?.accessibilityLabel)
    }

    @Test
    fun `a four-slot bar from an older Core has no info slot`() {
        val bar =
            decodeBar(
                """{"back":null,"navigation":null,"primary":$infoAction,"secondary":null}""",
            )

        assertNull(bar.info)
        assertEquals("Info", bar.primary?.label)
    }

    @Test
    fun `the info slot trails before actions`() {
        val action =
            ActionSpec(
                interactionId = "presentation.info",
                label = "Info",
                accessibilityLabel = "About this screen",
                iconToken = null,
                enabled = true,
                tone = ActionTone.Standard,
                shortcut = null,
            )
        val model =
            ContextBarModel(
                ContextBar(back = null, navigation = null, primary = action, secondary = action, info = action),
            )

        assertEquals(listOf(ContextBarSlot.Info, ContextBarSlot.Secondary), model.trailingSlots)
        assertTrue(model.hasPrimary)
    }
}
