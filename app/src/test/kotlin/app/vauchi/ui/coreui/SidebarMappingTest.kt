// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import app.vauchi.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin mapping between core's `MobileTabInfo` (SF-Symbol icon
 * names + snake_case screen ids) and Android's local `Screen` enum +
 * Material Icons. Lives outside the bottom-nav rendering so it can be
 * unit-tested without launching the activity.
 */
class SidebarMappingTest {
    @Test
    fun `screenForCoreTabId maps the five mobile tab ids`() {
        assertEquals(Screen.Home, screenForCoreTabId("my_info"))
        assertEquals(Screen.Contacts, screenForCoreTabId("contacts"))
        assertEquals(Screen.ExchangeModePicker, screenForCoreTabId("exchange"))
        assertEquals(Screen.Labels, screenForCoreTabId("groups"))
        assertEquals(Screen.More, screenForCoreTabId("more"))
    }

    @Test
    fun `screenForCoreTabId returns null for unknown ids`() {
        assertNull(screenForCoreTabId("not_a_real_screen"))
        assertNull(screenForCoreTabId(""))
    }

    @Test
    fun `coreTabIdForScreen reverses the five mobile tabs`() {
        assertEquals("my_info", coreTabIdForScreen(Screen.Home))
        assertEquals("contacts", coreTabIdForScreen(Screen.Contacts))
        assertEquals("exchange", coreTabIdForScreen(Screen.ExchangeModePicker))
        assertEquals("groups", coreTabIdForScreen(Screen.Labels))
        assertEquals("more", coreTabIdForScreen(Screen.More))
    }

    @Test
    fun `coreTabIdForScreen folds Exchange sub-screens to the exchange tab`() {
        assertEquals("exchange", coreTabIdForScreen(Screen.MultiStageExchange))
        assertEquals("exchange", coreTabIdForScreen(Screen.NfcExchange))
        assertEquals("exchange", coreTabIdForScreen(Screen.BleExchange))
    }

    @Test
    fun `coreTabIdForScreen returns null for non-top-level screens`() {
        assertNull(coreTabIdForScreen(Screen.ContactDetail))
        assertNull(coreTabIdForScreen(Screen.Settings))
    }

    @Test
    fun `materialIconForCoreIcon maps the five SF Symbols core emits for mobile tabs`() {
        // Names come from `tab_info_for` in core/vauchi-app/src/ui/app_engine/navigation.rs
        assertEquals(MaterialIconName.PERSON, materialIconNameForCoreIcon("person.crop.rectangle"))
        assertEquals(MaterialIconName.PEOPLE, materialIconNameForCoreIcon("person.2"))
        assertEquals(MaterialIconName.QR_CODE, materialIconNameForCoreIcon("qrcode"))
        assertEquals(MaterialIconName.GROUP, materialIconNameForCoreIcon("folder"))
        assertEquals(MaterialIconName.MORE_HORIZ, materialIconNameForCoreIcon("ellipsis.circle"))
    }

    @Test
    fun `materialIconForCoreIcon falls back to MORE_HORIZ for unknown icons`() {
        assertEquals(MaterialIconName.MORE_HORIZ, materialIconNameForCoreIcon("not.a.real.icon"))
    }
}
