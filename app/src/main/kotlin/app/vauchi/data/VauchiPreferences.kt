// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.SharedPreferences

/**
 * Thin wrapper around [SharedPreferences] for app-level settings.
 * Takes a [SharedPreferences] instance directly (not a Context) so it
 * can be tested with Robolectric without needing a full VauchiRepository.
 */
class VauchiPreferences(
    private val prefs: SharedPreferences,
) {
    companion object {
        const val PREFS_NAME = "vauchi_settings"
        const val KEY_RELAY_URL = "relay_url"
        const val DEFAULT_RELAY_URL = "https://relay.vauchi.app"

        const val KEY_REDUCE_MOTION = "accessibility_reduce_motion"
        const val KEY_HIGH_CONTRAST = "accessibility_high_contrast"
        const val KEY_LARGE_TOUCH_TARGETS = "accessibility_large_touch_targets"
    }

    fun getRelayUrl(): String = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(url: String) {
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
    }

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
