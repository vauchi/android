// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import uniffi.vauchi_platform.MobileAppPreferences
import uniffi.vauchi_platform.MobileTheme
import uniffi.vauchi_platform.MobileThemeMode
import uniffi.vauchi_platform.VauchiPlatform
import uniffi.vauchi_platform.getAvailableThemes
import uniffi.vauchi_platform.getDefaultThemeId
import uniffi.vauchi_platform.getTheme

/**
 * Manages theme selection and application.
 *
 * Source of truth is the core `app_preferences` row, accessed through
 * [VauchiPlatform.appPreferences] / [VauchiPlatform.setAppPreferences].
 * The Settings screen `Component::Dropdown` for theme writes the same
 * row via the AppEngine intercept, so this manager and the inline
 * dropdown stay in sync without a back-channel.
 *
 * Before [attachVauchi] runs (cold start, before VauchiRepository
 * initialises), the manager falls back to legacy SharedPreferences and
 * the system default theme — the Compose theme provider recomposes
 * once the row becomes available.
 */
class ThemeManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var vauchi: VauchiPlatform? = null

    /** Currently selected theme */
    var currentTheme: MobileTheme? by mutableStateOf(null)
        private set

    /** All available themes */
    var availableThemes: List<MobileTheme> by mutableStateOf(emptyList())
        private set

    /** Whether to follow system appearance */
    var followSystem: Boolean by mutableStateOf(true)
        private set

    /** Selected theme ID (`null` when following system). */
    var selectedThemeId: String? by mutableStateOf(null)
        private set

    init {
        loadThemes()
    }

    private fun loadThemes() {
        try {
            availableThemes = getAvailableThemes()
            applySelectedTheme(isDarkMode = false) // Will be updated when composable reads system setting
        } catch (e: UnsatisfiedLinkError) {
            availableThemes = emptyList()
        }
    }

    /**
     * Wire this manager to the live [VauchiPlatform] instance so
     * subsequent reads/writes flow through the core `app_preferences`
     * row. Called once by `VauchiRepository.platform()` after the
     * platform finishes lazy initialisation. Re-applies the theme
     * immediately so Compose theme observers pick up any value just
     * migrated from legacy SharedPreferences.
     */
    fun attachVauchi(vauchi: VauchiPlatform) {
        this.vauchi = vauchi
        applySelectedTheme(isDarkMode = false)
    }

    private fun loadPrefsOrFallback(): MobileAppPreferences {
        val v = vauchi
        if (v != null) {
            try {
                return v.appPreferences()
            } catch (_: Exception) {
                // Fall through to SharedPreferences-backed fallback.
            }
        }
        return MobileAppPreferences(
            themeId = prefs.getString(KEY_SELECTED_THEME, null),
            languageCode = null,
            followSystemTheme = prefs.getBoolean(KEY_FOLLOW_SYSTEM, true),
            followSystemLanguage = true,
        )
    }

    /**
     * Apply the currently selected theme.
     * @param isDarkMode Current system dark mode setting
     */
    fun applySelectedTheme(isDarkMode: Boolean) {
        val p = loadPrefsOrFallback()
        followSystem = p.followSystemTheme
        selectedThemeId = p.themeId
        try {
            currentTheme =
                if (!p.followSystemTheme && p.themeId != null) {
                    getTheme(p.themeId!!)
                } else {
                    val defaultId = getDefaultThemeId(isDarkMode)
                    getTheme(defaultId)
                }
        } catch (e: UnsatisfiedLinkError) {
            currentTheme = null
        }
    }

    /**
     * Select a theme by ID.
     */
    fun selectTheme(
        themeId: String,
        isDarkMode: Boolean,
    ) {
        persist(themeId = themeId, followSystemTheme = false)
        applySelectedTheme(isDarkMode)
    }

    /**
     * Reset to follow system appearance.
     */
    fun resetToSystem(isDarkMode: Boolean) {
        persist(themeId = null, followSystemTheme = true)
        applySelectedTheme(isDarkMode)
    }

    private fun persist(
        themeId: String?,
        followSystemTheme: Boolean,
    ) {
        val v = vauchi
        if (v != null) {
            try {
                val current = v.appPreferences()
                v.setAppPreferences(
                    MobileAppPreferences(
                        themeId = themeId,
                        languageCode = current.languageCode,
                        followSystemTheme = followSystemTheme,
                        followSystemLanguage = current.followSystemLanguage,
                    ),
                )
                return
            } catch (_: Exception) {
                // Fall through to SharedPreferences-backed fallback.
            }
        }
        prefs.edit {
            putBoolean(KEY_FOLLOW_SYSTEM, followSystemTheme)
            if (themeId == null) remove(KEY_SELECTED_THEME) else putString(KEY_SELECTED_THEME, themeId)
        }
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

        fun getInstance(context: Context): ThemeManager =
            instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also { instance = it }
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
