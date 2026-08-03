// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `unknown text style is rejected rather than silently defaulted`() {
        // Core projects the retired `Title` variant as "heading"
        // (prepared_surface/project.rs), so "title" is not a wire value.
        val failure =
            assertFailsWith<PresentationProtocolException> {
                parseTextRole("title")
            }

        assertTrue(
            failure.message!!.contains("title"),
            "rejection must name the offending value, got: ${failure.message}",
        )
    }
}
