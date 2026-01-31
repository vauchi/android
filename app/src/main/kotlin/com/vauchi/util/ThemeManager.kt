// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import uniffi.vauchi_mobile.MobileTheme
import uniffi.vauchi_mobile.MobileThemeMode
import uniffi.vauchi_mobile.getAvailableThemes
import uniffi.vauchi_mobile.getDefaultThemeId
import uniffi.vauchi_mobile.getTheme

/**
 * Manages theme selection and application.
 * Integrates with vauchi-mobile for theme definitions.
 */
class ThemeManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Currently selected theme */
    var currentTheme: MobileTheme? by mutableStateOf(null)
        private set

    /** All available themes */
    var availableThemes: List<MobileTheme> by mutableStateOf(emptyList())
        private set

    /** Whether to follow system appearance */
    var followSystem: Boolean by mutableStateOf(true)
        private set

    /** Selected theme ID */
    var selectedThemeId: String?
        get() = prefs.getString(KEY_SELECTED_THEME, null)
        private set(value) {
            prefs.edit { putString(KEY_SELECTED_THEME, value) }
        }

    init {
        followSystem = prefs.getBoolean(KEY_FOLLOW_SYSTEM, true)
        loadThemes()
    }

    private fun loadThemes() {
        availableThemes = getAvailableThemes()
        applySelectedTheme(isDarkMode = false) // Will be updated when composable reads system setting
    }

    /**
     * Apply the currently selected theme.
     * @param isDarkMode Current system dark mode setting
     */
    fun applySelectedTheme(isDarkMode: Boolean) {
        currentTheme = if (!followSystem && selectedThemeId != null) {
            getTheme(selectedThemeId!!)
        } else {
            val defaultId = getDefaultThemeId(isDarkMode)
            getTheme(defaultId)
        }
    }

    /**
     * Select a theme by ID.
     */
    fun selectTheme(themeId: String, isDarkMode: Boolean) {
        followSystem = false
        prefs.edit { putBoolean(KEY_FOLLOW_SYSTEM, false) }
        selectedThemeId = themeId
        applySelectedTheme(isDarkMode)
    }

    /**
     * Reset to follow system appearance.
     */
    fun resetToSystem(isDarkMode: Boolean) {
        followSystem = true
        prefs.edit { putBoolean(KEY_FOLLOW_SYSTEM, true) }
        selectedThemeId = null
        applySelectedTheme(isDarkMode)
    }

    /** Get dark themes */
    val darkThemes: List<MobileTheme>
        get() = availableThemes.filter { it.mode == MobileThemeMode.DARK }

    /** Get light themes */
    val lightThemes: List<MobileTheme>
        get() = availableThemes.filter { it.mode == MobileThemeMode.LIGHT }

    companion object {
        private const val PREFS_NAME = "vauchi_theme_settings"
        private const val KEY_SELECTED_THEME = "selected_theme_id"
        private const val KEY_FOLLOW_SYSTEM = "follow_system"

        @Volatile
        private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Convert hex color string to Compose Color.
 */
fun hexToColor(hex: String): Color {
    val colorString = hex.removePrefix("#")
    return try {
        Color(android.graphics.Color.parseColor("#$colorString"))
    } catch (e: Exception) {
        Color.Transparent
    }
}
