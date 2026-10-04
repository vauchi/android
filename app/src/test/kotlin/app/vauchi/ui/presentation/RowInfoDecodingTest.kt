// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A row can explain its item (vauchi/private#479): Core sends an optional
 * `info` action whose overlay holds the text. A row from an older Core has
 * no such key and decodes as before.
 */
class RowInfoDecodingTest {
    private val row =
        """
        {"title":"Home address","subtitle":null,"detail":null,"icon_token":null,
         "image_data":null,"fallback_text":null,"selected":false,"enabled":true,
         "activation":null,"secondary_actions":[],"controls":[],
         "accessibility":{"label":"Home address","description":null}%s}
        """.trimIndent()

    @Test
    fun `a row decodes Core's info action`() {
        val decoded =
            PresentationProtocol.decodeRow(
                row.format(
                    ""","info":{"interaction_id":"surface.7.interaction.3","label":"Info",
                    "accessibility_label":"About Home address","icon_token":null,
                    "enabled":true,"shortcut":null}""",
                ),
            )

        assertEquals("surface.7.interaction.3", decoded.info?.interactionId)
        assertEquals("About Home address", decoded.info?.accessibilityLabel)
    }

    @Test
    fun `a row from an older Core has no info action`() {
        val decoded = PresentationProtocol.decodeRow(row.format(""))

        assertNull(decoded.info)
    }
}
