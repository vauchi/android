// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import com.vauchi.ui.onboarding.OnboardingData
import com.vauchi.ui.onboarding.OnboardingStep
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for onboarding flow
 * Based on: features/onboarding.feature
 */
class OnboardingTest {

    // MARK: - OnboardingStep Tests

    @Test
    fun `onboarding steps have correct order`() {
        val steps = OnboardingStep.entries
        assertEquals(6, steps.size)
        assertEquals(OnboardingStep.Welcome, steps[0])
        assertEquals(OnboardingStep.CreateIdentity, steps[1])
        assertEquals(OnboardingStep.AddFields, steps[2])
        assertEquals(OnboardingStep.Preview, steps[3])
        assertEquals(OnboardingStep.Security, steps[4])
        assertEquals(OnboardingStep.Ready, steps[5])
    }

    // MARK: - OnboardingData Tests

    @Test
    fun `onboarding data default values are empty`() {
        val data = OnboardingData()
        assertEquals("", data.displayName)
        assertEquals("", data.phone)
        assertEquals("", data.email)
    }

    @Test
    fun `onboarding data copy works correctly`() {
        val data = OnboardingData(
            displayName = "Alice",
            phone = "+1234567890",
            email = "alice@example.com"
        )

        assertEquals("Alice", data.displayName)
        assertEquals("+1234567890", data.phone)
        assertEquals("alice@example.com", data.email)

        val updated = data.copy(displayName = "Bob")
        assertEquals("Bob", updated.displayName)
        assertEquals("+1234567890", updated.phone)
        assertEquals("alice@example.com", updated.email)
    }

    @Test
    fun `onboarding data allows empty optional fields`() {
        val data = OnboardingData(
            displayName = "Alice",
            phone = "",
            email = ""
        )

        assertEquals("Alice", data.displayName)
        assertTrue(data.phone.isEmpty())
        assertTrue(data.email.isEmpty())
    }

    @Test
    fun `phone field preserves international format`() {
        val data = OnboardingData(phone = "+41 79 123 45 67")
        assertEquals("+41 79 123 45 67", data.phone)
    }

    @Test
    fun `email field preserves case`() {
        val data = OnboardingData(email = "Alice.Smith@Example.COM")
        assertEquals("Alice.Smith@Example.COM", data.email)
    }
}
