// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import app.vauchi.data.getAppPreferences
import app.vauchi.data.setAppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.vauchi_platform.MobileAppPreferences
import uniffi.vauchi_platform.MobileTheme
import uniffi.vauchi_platform.MobileThemeMode
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.getAvailableThemes
import uniffi.vauchi_platform.getDefaultThemeId
import uniffi.vauchi_platform.getTheme

/**
 * Manages theme selection and application.
 *
 * Source of truth is the core `app_preferences` row, accessed through
 * `DomainCommand::{Get,Set}AppPreferences` on `PlatformAppEngine`.
 * The Settings screen `Component::Dropdown` for theme writes the same
 * row via the AppEngine intercept, so this manager and the inline
 * dropdown stay in sync without a back-channel.
 *
 * Before [attachAppEngine] runs (cold start, before VauchiRepository
 * initialises), the manager falls back to legacy SharedPreferences and
 * the system default theme — the Compose theme provider recomposes
 * once the row becomes available.
 */
class ThemeManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var appEngine: PlatformAppEngine? = null

    /**
     * Currently selected theme.
     *
     * `MutableStateFlow` rather than `mutableStateOf` because this
     * manager is a singleton with a process-wide lifetime: it is
     * created on first `getInstance` (often from
     * `VauchiRepository.platform()` on a background thread) and
     * survives Activity recreation. Compose `mutableStateOf` is bound
     * to the snapshot system of the runtime that read it first; once
     * that runtime dies (config change, force-stop+relaunch) a fresh
     * Compose runtime reading the same singleton's state hits
     * "Reading a state that was created after the snapshot was
     * taken" — exactly the crash filed alongside this fix. Using a
     * `StateFlow` decouples the storage from any specific Compose
     * runtime; consumers observe via `collectAsState()`.
     */
    private val _currentTheme = MutableStateFlow<MobileTheme?>(null)
    val currentTheme: StateFlow<MobileTheme?> = _currentTheme.asStateFlow()

    /** All available themes. */
    private val _availableThemes = MutableStateFlow<List<MobileTheme>>(emptyList())
    val availableThemes: StateFlow<List<MobileTheme>> = _availableThemes.asStateFlow()

    /** Whether to follow system appearance. */
    private val _followSystem = MutableStateFlow(true)
    val followSystem: StateFlow<Boolean> = _followSystem.asStateFlow()

    /** Selected theme ID (`null` when following system). */
    private val _selectedThemeId = MutableStateFlow<String?>(null)
    val selectedThemeId: StateFlow<String?> = _selectedThemeId.asStateFlow()

    init {
        loadThemes()
    }

    private fun loadThemes() {
        try {
            _availableThemes.value = getAvailableThemes()
            applySelectedTheme(isDarkMode = false) // Will be updated when composable reads system setting
        } catch (e: UnsatisfiedLinkError) {
            _availableThemes.value = emptyList()
        }
    }

    /**
     * Wire this manager to the live [PlatformAppEngine] instance so
     * subsequent reads/writes flow through the core `app_preferences`
     * row. Called once by `VauchiRepository.platform()` after the
     * platform finishes lazy initialisation. Re-applies the theme
     * immediately so Compose theme observers pick up any value just
     * migrated from legacy SharedPreferences.
     */
    fun attachAppEngine(appEngine: PlatformAppEngine) {
        this.appEngine = appEngine
        applySelectedTheme(isDarkMode = false)
    }

    private fun loadPrefsOrFallback(): MobileAppPreferences {
        val engine = appEngine
        if (engine != null) {
            try {
                return engine.getAppPreferences()
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
        _followSystem.value = p.followSystemTheme
        _selectedThemeId.value = p.themeId
        try {
            _currentTheme.value =
                if (!p.followSystemTheme && p.themeId != null) {
                    getTheme(p.themeId!!)
                } else {
                    val defaultId = getDefaultThemeId(isDarkMode)
                    getTheme(defaultId)
                }
        } catch (e: UnsatisfiedLinkError) {
            _currentTheme.value = null
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
        val engine = appEngine
        if (engine != null) {
            try {
                val current = engine.getAppPreferences()
                engine.setAppPreferences(
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

    /** Get dark themes (snapshot of current `availableThemes` value). */
    val darkThemes: List<MobileTheme>
        get() = _availableThemes.value.filter { it.mode == MobileThemeMode.DARK }

    /** Get light themes (snapshot of current `availableThemes` value). */
    val lightThemes: List<MobileTheme>
        get() = _availableThemes.value.filter { it.mode == MobileThemeMode.LIGHT }

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
