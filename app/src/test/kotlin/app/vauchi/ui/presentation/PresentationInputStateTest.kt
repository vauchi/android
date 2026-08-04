// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationInputStateTest {
    @Test
    fun `native edits update the displayed value before the core echo`() {
        val state = PresentationInputState(initialValue = "")

        assertEquals("P", state.accept("P", maxLength = 100))
        assertEquals("P", state.value)
        assertEquals("Pi", state.accept("Pi", maxLength = 100))
        assertEquals("Pi", state.value)
    }

    @Test
    fun `native edits enforce the prepared maximum length`() {
        val state = PresentationInputState(initialValue = "")

        assertEquals("Pixe", state.accept("Pixel", maxLength = 4))
        assertEquals("Pixe", state.value)
    }
}
