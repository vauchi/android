// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.model

import org.junit.Assert.*
import org.junit.Test

class PasswordStrengthTest {

    @Test
    fun `PasswordStrengthLevel has four values`() {
        val values = PasswordStrengthLevel.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(PasswordStrengthLevel.TooWeak))
        assertTrue(values.contains(PasswordStrengthLevel.Fair))
        assertTrue(values.contains(PasswordStrengthLevel.Strong))
        assertTrue(values.contains(PasswordStrengthLevel.VeryStrong))
    }

    @Test
    fun `PasswordStrengthLevel ordinal order is weakest first`() {
        assertTrue(PasswordStrengthLevel.TooWeak.ordinal < PasswordStrengthLevel.Fair.ordinal)
        assertTrue(PasswordStrengthLevel.Fair.ordinal < PasswordStrengthLevel.Strong.ordinal)
        assertTrue(PasswordStrengthLevel.Strong.ordinal < PasswordStrengthLevel.VeryStrong.ordinal)
    }

    @Test
    fun `PasswordStrengthResult default values`() {
        val result = PasswordStrengthResult()
        assertEquals(PasswordStrengthLevel.TooWeak, result.level)
        assertEquals("", result.description)
        assertEquals("", result.feedback)
        assertFalse(result.isAcceptable)
    }

    @Test
    fun `PasswordStrengthResult custom values`() {
        val result = PasswordStrengthResult(
            level = PasswordStrengthLevel.Strong,
            description = "Strong password",
            feedback = "Good job!",
            isAcceptable = true
        )
        assertEquals(PasswordStrengthLevel.Strong, result.level)
        assertEquals("Strong password", result.description)
        assertEquals("Good job!", result.feedback)
        assertTrue(result.isAcceptable)
    }

    @Test
    fun `PasswordStrengthResult copy preserves values`() {
        val original = PasswordStrengthResult(
            level = PasswordStrengthLevel.Fair,
            description = "Fair",
            feedback = "Add more characters",
            isAcceptable = false
        )
        val copy = original.copy(level = PasswordStrengthLevel.Strong, isAcceptable = true)
        assertEquals(PasswordStrengthLevel.Strong, copy.level)
        assertEquals("Fair", copy.description)
        assertEquals("Add more characters", copy.feedback)
        assertTrue(copy.isAcceptable)
    }

    @Test
    fun `PasswordStrengthResult equality`() {
        val a = PasswordStrengthResult(PasswordStrengthLevel.TooWeak, "Weak", "Try harder", false)
        val b = PasswordStrengthResult(PasswordStrengthLevel.TooWeak, "Weak", "Try harder", false)
        assertEquals(a, b)
    }

    @Test
    fun `PasswordStrengthResult inequality on different level`() {
        val a = PasswordStrengthResult(level = PasswordStrengthLevel.TooWeak)
        val b = PasswordStrengthResult(level = PasswordStrengthLevel.VeryStrong)
        assertNotEquals(a, b)
    }
}
