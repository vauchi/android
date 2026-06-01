// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core-driven exchange screens emit i18n KEYS for some labels (ADR-038);
 * the humble renderer resolves only key-shaped strings via t(), leaving plain
 * English labels untouched. If an English label were mistaken for a key,
 * core's get_string would surface it as "Missing: <label>" — this guards that.
 */
class ScreenRendererI18nTest {
    @Test
    fun `dotted lowercase strings are treated as i18n keys`() {
        assertTrue(isI18nKey("exchange.mode.nfc_send"))
        assertTrue(isI18nKey("exchange.nfc.choose_role"))
        assertTrue(isI18nKey("action.cancel"))
    }

    @Test
    fun `plain english labels are not treated as keys`() {
        assertFalse(isI18nKey("Tap tap"))
        assertFalse(isI18nKey("Tap to Send"))
        assertFalse(isI18nKey("Glance"))
        assertFalse(isI18nKey("Continue"))
        assertFalse(isI18nKey("Requires USB port"))
        assertFalse(isI18nKey(""))
        assertFalse(isI18nKey("nodots"))
    }
}
