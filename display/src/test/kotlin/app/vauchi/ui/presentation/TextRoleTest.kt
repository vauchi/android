// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core's `PresentationTextStyle` is a closed enum
 * (`core/vauchi-core/src/platform/presentation/surface/nodes.rs`). These
 * tests pin the shell's obligation to honour every variant.
 *
 * The distinctness test exists because exhaustiveness alone cannot catch
 * the failure it guards: a `when` with every branch present still proves
 * only that `Monospace` was *handled*, never that it resolved to a
 * monospaced face. Both checks are needed.
 *
 * CC-27 evidence — observed failing, then passing once corrected. With
 * `Monospace` returning `monospaced = false`, which compiles cleanly and
 * keeps the `when` exhaustive, `monospace resolves to a monospaced face`
 * and `every text role resolves to a distinct presentation` both failed
 * (6 tests, 2 failures) while the other four stayed green. Restoring
 * `monospaced = true` returned 6/0. That mutation is the exact shape of
 * the bug this shell shipped.
 */
class TextRoleTest {
    @Test
    fun `every wire text style parses to its role`() {
        val expected =
            mapOf(
                "heading" to TextRole.Heading,
                "body" to TextRole.Body,
                "caption" to TextRole.Caption,
                "monospace" to TextRole.Monospace,
                "muted" to TextRole.Muted,
            )

        expected.forEach { (wire, role) ->
            assertEquals(role, parseTextRole(wire), "wire value '$wire'")
        }
    }

    @Test
    fun `every text role resolves to a distinct presentation`() {
        val resolved = TextRole.entries.associateWith(::textRoleStyle)

        assertEquals(
            TextRole.entries.size,
            resolved.values.toSet().size,
            "each role must resolve to its own presentation; " +
                "a collision means a role was silently folded into another",
        )
    }

    @Test
    fun `monospace resolves to a monospaced face`() {
        val style = textRoleStyle(TextRole.Monospace)

        assertTrue(style.monospaced, "Monospace must select a monospaced face")
        assertEquals(TextTypography.BodyLarge, style.typography)
    }

    @Test
    fun `muted resolves to reduced emphasis without changing size`() {
        val muted = textRoleStyle(TextRole.Muted)
        val body = textRoleStyle(TextRole.Body)

        assertTrue(muted.muted, "Muted must reduce emphasis")
        assertEquals(body.typography, muted.typography, "Muted differs by emphasis, not size")
        assertTrue(!body.muted, "Body must stay full emphasis")
    }

    @Test
    fun `heading and caption differ in size from body`() {
        assertEquals(TextTypography.TitleLarge, textRoleStyle(TextRole.Heading).typography)
        assertEquals(TextTypography.BodySmall, textRoleStyle(TextRole.Caption).typography)
        assertEquals(TextTypography.BodyLarge, textRoleStyle(TextRole.Body).typography)
    }

    @Test
    fun `unknown text style yields null so the caller can fall back`() {
        // Core projects the retired `Title` variant as "heading"
        // (prepared_surface/project.rs), so "title" is not a wire value.
        assertNull(parseTextRole("title"))
        assertNull(parseTextRole(""))
    }

    @Test
    fun `an unknown text style still renders its text as body`() {
        val node =
            PresentationProtocol
                .decodeEnvelope(envelopeWithTextNode(style = "some_future_role"))
                .commands
                .filterIsInstance<PresentationCommand.ReplaceSurface>()
                .single()
                .surface
                .nodes
                .filterIsInstance<PresentationNode.Text>()
                .single()

        assertEquals(TextRole.Body, node.style)
        assertEquals("hello", node.content)
    }

    private fun envelopeWithTextNode(style: String): String =
        """
        {"commands":[{"ReplaceSurface":{"surface":{
          "surface_id":"main",
          "revision":1,
          "title":"t",
          "subtitle":null,
          "accessibility_label":"t",
          "layout":"scroll",
          "tokens":{
            "spacing_small":4,
            "spacing_medium":8,
            "spacing_large":16,
            "corner_radius":8,
            "minimum_target_size":44
          },
          "nodes":[{"Text":{
            "id":null,
            "content":"hello",
            "style":"$style",
            "accessibility":{"label":"hello","description":null}
          }}]
        }}}]}
        """.trimIndent()
}
