// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.content.SharedPreferences

/**
 * Thin wrapper around [SharedPreferences] for app-level settings.
 * Takes a [SharedPreferences] instance directly (not a Context) so it
 * can be tested with Robolectric without needing a full VauchiRepository.
 */
class VauchiPreferences(private val prefs: SharedPreferences) {

    companion object {
        const val PREFS_NAME = "vauchi_settings"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_DEMO_CONTACT_DISMISSED = "demo_contact_dismissed"
        const val DEFAULT_RELAY_URL = "wss://relay.vauchi.app"

        // Accessibility settings keys
        const val KEY_REDUCE_MOTION = "accessibility_reduce_motion"
        const val KEY_HIGH_CONTRAST = "accessibility_high_contrast"
        const val KEY_LARGE_TOUCH_TARGETS = "accessibility_large_touch_targets"
    }

    // Relay URL
    fun getRelayUrl(): String =
        prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(url: String) {
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
    }

    // Onboarding state
    fun hasCompletedOnboarding(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    // Demo contact
    fun hasDismissedDemoContact(): Boolean =
        prefs.getBoolean(KEY_DEMO_CONTACT_DISMISSED, false)

    fun setDemoContactDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO_CONTACT_DISMISSED, dismissed).apply()
    }

    fun resetOnboarding() {
        prefs.edit()
            .remove(KEY_ONBOARDING_COMPLETED)
            .remove(KEY_DEMO_CONTACT_DISMISSED)
            .apply()
    }

    // Accessibility settings
    fun getReduceMotion(): Boolean = prefs.getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
    }

    fun getHighContrast(): Boolean = prefs.getBoolean(KEY_HIGH_CONTRAST, false)

    fun setHighContrast(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    fun getLargeTouchTargets(): Boolean = prefs.getBoolean(KEY_LARGE_TOUCH_TARGETS, false)

    fun setLargeTouchTargets(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LARGE_TOUCH_TARGETS, enabled).apply()
    }
}
