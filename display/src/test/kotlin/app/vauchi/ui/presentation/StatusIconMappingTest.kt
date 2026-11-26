// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core decorates status rows with the same SF-Symbol-spelled icon tokens
 * it uses for navigation (`PresentationNode::Status { icon_token }`),
 * and the shell owes each one a Material glyph drawn before the title.
 * Before this mapping the lock screen's `lock` token was dropped and
 * the row read as the bare word "Locked".
 *
 * Unlike navigation, an unknown status token resolves to *nothing*: a
 * status row already carries a title, so a neutral placeholder glyph
 * would add noise, not meaning.
 */
class StatusIconMappingTest {
    /** Tokens core's screens put on status rows today (screen catalogue). */
    private val catalogueTokens =
        mapOf(
            "lock" to Icons.Filled.Lock,
            "warning" to Icons.Filled.Warning,
            "qrcode" to Icons.Filled.QrCode2,
            "devices" to Icons.Filled.Devices,
            "people" to Icons.Filled.People,
            "swap" to Icons.Filled.SwapHoriz,
            "checkmark.shield" to Icons.Filled.VerifiedUser,
            "checkmark.seal" to Icons.Filled.Verified,
            "info" to Icons.Filled.Info,
            "exclamationmark.shield" to Icons.Filled.GppMaybe,
            "delete" to Icons.Filled.Delete,
            "key" to Icons.Filled.Key,
            "eye" to Icons.Filled.Visibility,
        )

    @Test
    fun `every catalogue status token resolves to its material glyph`() {
        catalogueTokens.forEach { (token, expected) ->
            assertEquals(
                expected.name,
                statusIcon(token)?.name,
                "token '$token' resolved to the wrong glyph",
            )
        }
    }

    @Test
    fun `an unrecognised status token draws no glyph`() {
        assertNull(statusIcon("wallet.bifold"))
        assertNull(statusIcon(""))
    }

    @Test
    fun `every status glyph is a filled variant`() {
        val outlined =
            catalogueTokens.keys
                .mapNotNull { statusIcon(it)?.name }
                .filterNot { it.substringBeforeLast('.').endsWith("Filled") }

        assertTrue(outlined.isEmpty(), "non-filled glyphs in the status table: $outlined")
    }
}
