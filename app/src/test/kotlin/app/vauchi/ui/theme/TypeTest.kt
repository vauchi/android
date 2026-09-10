// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.vauchi.R
import app.vauchi.ui.presentation.TextRole
import app.vauchi.ui.presentation.TextTypography
import app.vauchi.ui.presentation.textRoleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins the brand type ramp: each [TextRole] must resolve to the family
 * the brand assigns it (heading -> Bricolage Grotesque, body/caption ->
 * Hanken Grotesk, monospace -> JetBrains Mono), not just the right size
 * step.
 */
class TypeTest {
    private fun resolvedFamily(role: TextRole): FontFamily? {
        val style = textRoleStyle(role)
        if (style.monospaced) return MonospaceFontFamily
        return when (style.typography) {
            TextTypography.TitleLarge -> Typography.titleLarge.fontFamily
            TextTypography.BodyLarge -> Typography.bodyLarge.fontFamily
            TextTypography.BodySmall -> Typography.bodySmall.fontFamily
        }
    }

    @Test
    fun `heading resolves to Bricolage Grotesque`() {
        assertSame(BricolageGrotesque, resolvedFamily(TextRole.Heading))
    }

    @Test
    fun `body and muted resolve to Hanken Grotesk`() {
        assertSame(HankenGrotesk, resolvedFamily(TextRole.Body))
        assertSame(HankenGrotesk, resolvedFamily(TextRole.Muted))
    }

    @Test
    fun `caption resolves to Hanken Grotesk`() {
        assertSame(HankenGrotesk, resolvedFamily(TextRole.Caption))
    }

    @Test
    fun `monospace resolves to JetBrains Mono`() {
        assertSame(JetBrainsMono, resolvedFamily(TextRole.Monospace))
    }

    @Test
    fun `heading and body weights follow the brand ramp`() {
        assertEquals(FontWeight.Bold, Typography.titleLarge.fontWeight)
        assertEquals(FontWeight.Normal, Typography.bodyLarge.fontWeight)
        assertEquals(FontWeight.Normal, Typography.bodySmall.fontWeight)
    }

    @Test
    fun `the five brand font resources exist`() {
        val ids =
            setOf(
                R.font.bricolage_grotesque,
                R.font.hanken_grotesk,
                R.font.hanken_grotesk_italic,
                R.font.jetbrains_mono,
                R.font.jetbrains_mono_italic,
            )

        assertEquals(5, ids.size, "each brand font resource id must be distinct")
    }
}
