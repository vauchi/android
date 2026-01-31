// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VauchiPreferencesTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var vauchiPrefs: VauchiPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vauchiPrefs = VauchiPreferences(prefs)
    }

    // --- Relay URL ---

    @Test
    fun `getRelayUrl returns default when not set`() {
        assertEquals(VauchiPreferences.DEFAULT_RELAY_URL, vauchiPrefs.getRelayUrl())
    }

    @Test
    fun `setRelayUrl persists value`() {
        vauchiPrefs.setRelayUrl("wss://custom.relay")
        assertEquals("wss://custom.relay", vauchiPrefs.getRelayUrl())
    }

    @Test
    fun `setRelayUrl overwrites previous value`() {
        vauchiPrefs.setRelayUrl("wss://first")
        vauchiPrefs.setRelayUrl("wss://second")
        assertEquals("wss://second", vauchiPrefs.getRelayUrl())
    }

    // --- Onboarding ---

    @Test
    fun `hasCompletedOnboarding returns false by default`() {
        assertFalse(vauchiPrefs.hasCompletedOnboarding())
    }

    @Test
    fun `setOnboardingCompleted persists true`() {
        vauchiPrefs.setOnboardingCompleted(true)
        assertTrue(vauchiPrefs.hasCompletedOnboarding())
    }

    @Test
    fun `setOnboardingCompleted can set back to false`() {
        vauchiPrefs.setOnboardingCompleted(true)
        vauchiPrefs.setOnboardingCompleted(false)
        assertFalse(vauchiPrefs.hasCompletedOnboarding())
    }

    // --- Demo Contact ---

    @Test
    fun `hasDismissedDemoContact returns false by default`() {
        assertFalse(vauchiPrefs.hasDismissedDemoContact())
    }

    @Test
    fun `setDemoContactDismissed persists true`() {
        vauchiPrefs.setDemoContactDismissed(true)
        assertTrue(vauchiPrefs.hasDismissedDemoContact())
    }

    // --- Reset Onboarding ---

    @Test
    fun `resetOnboarding clears onboarding and demo contact flags`() {
        vauchiPrefs.setOnboardingCompleted(true)
        vauchiPrefs.setDemoContactDismissed(true)

        vauchiPrefs.resetOnboarding()

        assertFalse(vauchiPrefs.hasCompletedOnboarding())
        assertFalse(vauchiPrefs.hasDismissedDemoContact())
    }

    @Test
    fun `resetOnboarding does not affect other settings`() {
        vauchiPrefs.setRelayUrl("wss://custom.relay")
        vauchiPrefs.setReduceMotion(true)
        vauchiPrefs.setOnboardingCompleted(true)

        vauchiPrefs.resetOnboarding()

        assertEquals("wss://custom.relay", vauchiPrefs.getRelayUrl())
        assertTrue(vauchiPrefs.getReduceMotion())
    }

    // --- Accessibility ---

    @Test
    fun `getReduceMotion returns false by default`() {
        assertFalse(vauchiPrefs.getReduceMotion())
    }

    @Test
    fun `setReduceMotion persists value`() {
        vauchiPrefs.setReduceMotion(true)
        assertTrue(vauchiPrefs.getReduceMotion())
    }

    @Test
    fun `getHighContrast returns false by default`() {
        assertFalse(vauchiPrefs.getHighContrast())
    }

    @Test
    fun `setHighContrast persists value`() {
        vauchiPrefs.setHighContrast(true)
        assertTrue(vauchiPrefs.getHighContrast())
    }

    @Test
    fun `getLargeTouchTargets returns false by default`() {
        assertFalse(vauchiPrefs.getLargeTouchTargets())
    }

    @Test
    fun `setLargeTouchTargets persists value`() {
        vauchiPrefs.setLargeTouchTargets(true)
        assertTrue(vauchiPrefs.getLargeTouchTargets())
    }
}
