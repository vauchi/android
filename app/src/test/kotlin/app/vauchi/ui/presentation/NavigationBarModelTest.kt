// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationBarModelTest {
    @Test
    fun `an empty item list hides the bar`() {
        val model = NavigationBarModel(emptyList())

        assertTrue(!model.isVisible)
        assertNull(model.selectedIndex)
        assertNull(model.centerItem)
    }

    @Test
    fun `a non-empty item list shows the bar`() {
        val model = NavigationBarModel(listOf(item("contacts", selected = false)))

        assertTrue(model.isVisible)
    }

    @Test
    fun `selected index points at the selected item`() {
        val model =
            NavigationBarModel(
                listOf(
                    item("my_info", selected = false),
                    item("contacts", selected = true),
                    item("exchange", selected = false, iconToken = "qrcode"),
                ),
            )

        assertEquals(1, model.selectedIndex)
    }

    @Test
    fun `no selected item yields a null selected index`() {
        val model = NavigationBarModel(listOf(item("contacts", selected = false)))

        assertNull(model.selectedIndex)
    }

    @Test
    fun `the center item is the one carrying the exchange icon token`() {
        val exchange = item("exchange", selected = false, iconToken = "qrcode")
        val model =
            NavigationBarModel(
                listOf(item("my_info", selected = true), exchange, item("contacts", selected = false)),
            )

        assertEquals(1, model.centerItemIndex)
        assertEquals(exchange, model.centerItem)
    }

    @Test
    fun `no exchange icon token yields no center item`() {
        val model = NavigationBarModel(listOf(item("my_info", selected = true), item("contacts", selected = false)))

        assertNull(model.centerItem)
        assertNull(model.centerItemIndex)
    }

    private fun item(
        interactionId: String,
        selected: Boolean,
        iconToken: String? = "person.2",
        badgeCount: Int = 0,
    ): NavigationItem =
        NavigationItem(
            interactionId = interactionId,
            label = interactionId,
            accessibilityLabel = interactionId,
            iconToken = iconToken,
            selected = selected,
            badgeCount = badgeCount,
        )
}
