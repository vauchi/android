// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Core names every navigation destination with a platform-neutral icon
 * token in SF Symbol spelling
 * (`core/vauchi-app/src/ui/app_engine/navigation.rs`, `tab_metadata`),
 * and hands it to the shell as `ActionSpec.icon_token`
 * (`core/vauchi-app/src/ui/contextual_surface.rs`). These tests pin the
 * shell's obligation to resolve every one of those tokens to a native
 * Material glyph.
 *
 * The distinctness test exists because coverage alone cannot catch the
 * failure it guards: a mapping with an entry for all sixteen tokens
 * still proves only that each was *handled*, never that the user can
 * tell two destinations apart. Ten identical glyphs above ten labels is
 * the exact problem this mapping exists to fix, so "every token has an
 * icon" and "no two tokens share one" are both needed.
 *
 * The filled-variant test encodes a deliberate accessibility decision
 * rather than a taste: thin outline strokes lose definition at nav-row
 * size and for low-vision users, so an outline glyph slipping into the
 * table is a regression a reviewer would otherwise have to catch by eye.
 *
 * CC-27 evidence — observed failing, then passing once implemented.
 * Against a stub that answered `Icons.Filled.Home` for every token, the
 * three tests that discriminate — `every core navigation token resolves
 * to its material glyph`, `every navigation destination gets a distinct
 * glyph`, and `an unrecognised token falls back to a neutral glyph` —
 * failed (5 tests, 3 failures) while `every navigation glyph is a
 * filled variant` and the totality test stayed green, since Home is
 * itself filled and non-null. That is the precise shape of the bug this
 * shell shipped: the mapping present but not discriminating.
 */
class NavigationIconMappingTest {
    /**
     * Every token `tab_metadata` can emit, including the `house`
     * fallback it resolves unknown screens to. Kept as the full table
     * rather than the handful a menu shows today, so a screen becoming
     * reachable does not silently arrive without a glyph.
     */
    private val coreTokens =
        mapOf(
            "person.crop.rectangle" to Icons.Filled.ContactPage,
            "person.2" to Icons.Filled.People,
            "qrcode" to Icons.Filled.QrCode2,
            "folder" to Icons.Filled.Folder,
            "tag" to Icons.AutoMirrored.Filled.Label,
            "mappin.and.ellipse" to Icons.Filled.Place,
            "person.badge.plus" to Icons.Filled.PersonAdd,
            "gearshape" to Icons.Filled.Settings,
            "questionmark.circle" to Icons.AutoMirrored.Filled.Help,
            "key.horizontal" to Icons.Filled.VpnKey,
            "laptopcomputer" to Icons.Filled.Devices,
            "externaldrive" to Icons.Filled.Storage,
            "hand.raised" to Icons.Filled.PrivacyTip,
            "bubble.left.and.bubble.right" to Icons.Filled.Forum,
            "list.bullet.rectangle" to Icons.AutoMirrored.Filled.ListAlt,
            "house" to Icons.Filled.Home,
        )

    @Test
    fun `every core navigation token resolves to its material glyph`() {
        coreTokens.forEach { (token, expected) ->
            assertEquals(
                expected.name,
                navigationIcon(token).name,
                "token '$token' resolved to the wrong glyph",
            )
        }
    }

    @Test
    fun `every navigation destination gets a distinct glyph`() {
        val glyphsByName = coreTokens.keys.map { navigationIcon(it).name }

        val shared =
            glyphsByName
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }

        assertTrue(
            shared.isEmpty(),
            "destinations sharing one glyph are indistinguishable: $shared",
        )
    }

    @Test
    fun `an unrecognised token falls back to a neutral glyph`() {
        // A token core adds after this shell ships. It must not crash,
        // must not render nothing, and must not borrow another
        // destination's meaning — `Home` beside a label reading
        // "Wallet" is a worse lie than an unclaimed glyph.
        val fallback = navigationIcon("wallet.bifold")

        assertEquals(Icons.Filled.Apps.name, fallback.name)
    }

    @Test
    fun `the mapping is total over arbitrary input`() {
        listOf("", " ", "house.fill", "PERSON.2", "../../etc/passwd", "🔑")
            .forEach { token ->
                assertNotNull(
                    navigationIcon(token),
                    "token '$token' resolved to nothing",
                )
            }
    }

    @Test
    fun `every navigation glyph is a filled variant`() {
        // Outline strokes thin out at nav-row size and for low-vision
        // users; the filled set is the accessibility decision, so an
        // outline glyph reaching the table is a regression.
        val outlined =
            coreTokens.keys
                .map { navigationIcon(it).name }
                .filterNot { it.substringBeforeLast('.').endsWith("Filled") }

        assertTrue(
            outlined.isEmpty(),
            "non-filled glyphs in the navigation table: $outlined",
        )
    }
}
