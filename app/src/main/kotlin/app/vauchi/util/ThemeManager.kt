// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.vauchi_platform.MobileTheme
import uniffi.vauchi_platform.MobileThemeMode
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.getAvailableThemes
import uniffi.vauchi_platform.getDefaultThemeId
import uniffi.vauchi_platform.getTheme

/**
 * Manages theme selection and application.
 *
 * Source of truth is the `vauchi_theme_settings` SharedPreferences
 * store (OS-native, Category 1 — render-context). Core's
 * `RenderContext` is informed of changes via `setRenderContextJson`
 * so the Settings dropdown's `selected` value stays in sync (S4 of
 * `2026-05-16-settings-storage-by-sensitivity`).
 *
 * Before [attachAppEngine] runs (cold start, before VauchiRepository
 * initialises), reads still resolve against SharedPreferences and
 * Compose recomposes once the engine becomes available.
 */
class ThemeManager(
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var appEngine: PlatformAppEngine? = null

    /**
     * Currently selected theme.
     *
     * `MutableStateFlow` rather than `mutableStateOf` because this
     * manager is a singleton with a process-wide lifetime: it is
     * created on first `getInstance` (often from
     * `VauchiRepository.ensureInitialized()` on a background thread) and
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
        } catch (_: LinkageError) {
            _availableThemes.value = emptyList()
        }
        applySelectedTheme(isDarkMode = false)
    }

    /**
     * Wire this manager to the live [PlatformAppEngine] instance so
     * subsequent theme changes propagate to core's `RenderContext`.
     * Called once by `VauchiRepository.ensureInitialized()` after the platform
     * finishes lazy initialisation. Re-applies the theme and pushes
     * it to core so the Settings dropdown reflects what's on disk.
     */
    fun attachAppEngine(appEngine: PlatformAppEngine) {
        this.appEngine = appEngine
        applySelectedTheme(isDarkMode = false)
        pushRenderContext(appContext, appEngine)
    }

    private fun safeGetBoolean(
        key: String,
        default: Boolean,
    ): Boolean =
        try {
            prefs.getBoolean(key, default)
        } catch (_: ClassCastException) {
            default
        }

    private fun safeGetString(
        key: String,
        default: String?,
    ): String? =
        try {
            prefs.getString(key, default)
        } catch (_: ClassCastException) {
            default
        }

    /**
     * Apply the currently selected theme.
     * @param isDarkMode Current system dark mode setting
     */
    fun applySelectedTheme(isDarkMode: Boolean) {
        val followSystem = safeGetBoolean(KEY_FOLLOW_SYSTEM, true)
        val themeId = safeGetString(KEY_SELECTED_THEME, null)
        _followSystem.value = followSystem
        _selectedThemeId.value = themeId
        try {
            _currentTheme.value =
                if (!followSystem && themeId != null) {
                    getTheme(themeId)
                } else {
                    val defaultId = getDefaultThemeId(isDarkMode)
                    getTheme(defaultId)
                }
        } catch (_: LinkageError) {
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
        pushRenderContext(appContext, appEngine)
    }

    /**
     * Reset to follow system appearance.
     */
    fun resetToSystem(isDarkMode: Boolean) {
        persist(themeId = null, followSystemTheme = true)
        applySelectedTheme(isDarkMode)
        pushRenderContext(appContext, appEngine)
    }

    private fun persist(
        themeId: String?,
        followSystemTheme: Boolean,
    ) {
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
