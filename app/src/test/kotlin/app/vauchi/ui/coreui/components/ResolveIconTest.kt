// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the [resolveIcon] semantic-token → Material glyph map, with
 * focus on the exchange-mode tokens emitted by core's mode-selection
 * screen. An unmapped token must fall back to a stable glyph so the list
 * never renders blank.
 */
class ResolveIconTest {
    @Test
    fun `exchange mode tokens map to their material glyphs`() {
        assertEquals(Icons.Default.QrCodeScanner, resolveIcon("qrcode"))
        assertEquals(Icons.Default.Nfc, resolveIcon("nfc"))
        assertEquals(Icons.Default.Sensors, resolveIcon("bump"))
        assertEquals(Icons.Default.Vibration, resolveIcon("shake"))
        assertEquals(Icons.Default.AutoAwesome, resolveIcon("sparkles"))
        assertEquals(Icons.Default.TouchApp, resolveIcon("tap"))
        assertEquals(Icons.Default.Gesture, resolveIcon("gesture"))
        assertEquals(Icons.Default.Link, resolveIcon("link"))
        assertEquals(Icons.Default.Cable, resolveIcon("cable"))
    }

    @Test
    fun `token resolution is case insensitive`() {
        assertEquals(Icons.Default.QrCodeScanner, resolveIcon("QRCode"))
        assertEquals(Icons.Default.Cable, resolveIcon("CABLE"))
    }

    @Test
    fun `unknown token falls back to info glyph`() {
        assertEquals(Icons.Default.Info, resolveIcon("definitely-not-a-real-icon"))
        assertEquals(Icons.Default.Info, resolveIcon(""))
    }
}
