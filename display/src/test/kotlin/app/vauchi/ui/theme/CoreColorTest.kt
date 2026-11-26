// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** Core sends theme colours as `#rrggbb` (vauchi/private#814). */
class CoreColorTest {
    // @internal
    @Test
    fun `a six-digit colour paints opaque in either case`() {
        assertEquals(Color(0xFFB3261E), coreColor("#b3261e"))
        assertEquals(Color(0xFFB3261E), coreColor("#B3261E"))
        assertEquals(Color(0xFF00FF00), coreColor("00ff00"))
    }

    // @internal
    @Test
    fun `anything other than six hex digits paints transparent`() {
        for (hex in listOf("", "#", "#12345", "#80ff0000", "not-a-color", "#gg0000")) {
            assertEquals(Color.Transparent, coreColor(hex), hex)
        }
    }

    // @internal — the status and gesture bars are transparent over the
    // theme's background, so their icons follow its lightness.
    @Test
    fun `system bar icons are dark only on a light background`() {
        assertEquals(false, coreColor("#282a36").wantsDarkSystemBarIcons())
        assertEquals(true, coreColor("#eff1f5").wantsDarkSystemBarIcons())
    }
}
