// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import app.vauchi.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Core names Vauchi's own artwork with `pictogram.<group>.<name>` tokens
 * (the exchange-mode picker sends `pictogram.exchange.hover` and friends)
 * beside the SF-Symbol-spelled Material tokens. The shell owes each one
 * the bundled drawable `pictogram_<group>_<name>`, found by the token's
 * spelling alone — the shell never learns what an exchange mode is
 * (ADR-066).
 */
class PictogramIconMappingTest {
    private val exchangePictograms =
        mapOf(
            "pictogram.exchange.glance" to R.drawable.pictogram_exchange_glance,
            "pictogram.exchange.hover" to R.drawable.pictogram_exchange_hover,
            "pictogram.exchange.bump" to R.drawable.pictogram_exchange_bump,
            "pictogram.exchange.shake" to R.drawable.pictogram_exchange_shake,
            "pictogram.exchange.magic" to R.drawable.pictogram_exchange_magic,
            "pictogram.exchange.tap_tap" to R.drawable.pictogram_exchange_tap_tap,
            "pictogram.exchange.tap_hover_shake" to R.drawable.pictogram_exchange_tap_hover_shake,
            "pictogram.exchange.link" to R.drawable.pictogram_exchange_link,
            "pictogram.exchange.cable" to R.drawable.pictogram_exchange_cable,
        )

    @Test
    fun `a pictogram token resolves to its bundled drawable`() {
        assertEquals(
            IconSource.Drawable(R.drawable.pictogram_exchange_hover),
            navigationIconSource("pictogram.exchange.hover"),
        )
        assertEquals(
            IconSource.Drawable(R.drawable.pictogram_exchange_hover),
            statusIconSource("pictogram.exchange.hover"),
        )
    }

    @Test
    fun `every exchange mode pictogram core sends is bundled`() {
        exchangePictograms.forEach { (token, drawable) ->
            assertEquals(
                IconSource.Drawable(drawable),
                navigationIconSource(token),
                "token '$token' resolved to the wrong pictogram",
            )
        }
    }

    @Test
    fun `a material token still resolves to its glyph`() {
        assertEquals(
            Icons.Filled.QrCode2.name,
            (navigationIconSource("qrcode") as IconSource.Vector).image.name,
        )
        assertEquals(
            Icons.Filled.Lock.name,
            (statusIconSource("lock") as IconSource.Vector).image.name,
        )
    }

    @Test
    fun `an unbundled pictogram falls back like any unknown token`() {
        assertEquals(
            Icons.Filled.Apps.name,
            (navigationIconSource("pictogram.exchange.teleport") as IconSource.Vector).image.name,
        )
        assertNull(statusIconSource("pictogram.exchange.teleport"))
    }

    @Test
    fun `only the pictogram prefix reaches the bundled artwork`() {
        assertNull(pictogramDrawable("exchange.hover"))
        assertNull(pictogramDrawable("pictogram_exchange_hover"))
    }
}
