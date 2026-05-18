// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import app.vauchi.data.getAppPreferences
import app.vauchi.data.setAppPreferences
import uniffi.vauchi_platform.MobileAppPreferences
import uniffi.vauchi_platform.MobileLocale
import uniffi.vauchi_platform.MobileLocaleInfo
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.getAvailableLocales
import uniffi.vauchi_platform.getLocaleInfo
import uniffi.vauchi_platform.getString
import uniffi.vauchi_platform.getStringWithArgs
import uniffi.vauchi_platform.initLocales
import uniffi.vauchi_platform.parseLocaleCode
import java.io.File
import java.util.Locale

/**
 * Manages localization/internationalization.
 * Integrates with vauchi-platform for string translations.
 */
class LocalizationManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var appEngine: PlatformAppEngine? = null

    /** Currently selected locale */
    var currentLocale: MobileLocale by mutableStateOf(MobileLocale.ENGLISH)
        private set

    /** All available locales */
    var availableLocales: List<MobileLocaleInfo> by mutableStateOf(emptyList())
        private set

    /** Whether to follow system language */
    var followSystem: Boolean by mutableStateOf(true)
        private set

    /** Selected locale code (`null` when following system). */
    var selectedLocaleCode: String? by mutableStateOf(null)
        private set

    init {
        extractAndInitLocales(context)
        loadLocales()
    }

    /**
     * Wire this manager to the live [PlatformAppEngine] instance so
     * subsequent reads/writes flow through the core `app_preferences`
     * row. Called once by `VauchiRepository.platform()` after the
     * platform finishes lazy initialisation. Re-applies the locale
     * immediately so observers pick up any value just migrated from
     * legacy SharedPreferences.
     */
    fun attachAppEngine(appEngine: PlatformAppEngine) {
        this.appEngine = appEngine
        applySelectedLocale()
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
            themeId = null,
            languageCode = prefs.getString(KEY_SELECTED_LOCALE, null),
            followSystemTheme = true,
            followSystemLanguage = prefs.getBoolean(KEY_FOLLOW_SYSTEM, true),
        )
    }

    /**
     * Extract locale JSON files from assets to internal storage and initialize
     * the core i18n system. Runs once per install/update.
     */
    private fun extractAndInitLocales(context: Context) {
        val localesDir = File(context.filesDir, "locales")
        val versionFile = File(localesDir, ".version")
        val currentVersion = getAppVersionCode(context)

        // Skip extraction if already done for this app version
        if (versionFile.exists() && versionFile.readText().trim() == currentVersion) {
            try {
                initLocales(localesDir.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to initialize locales: native library not found", e)
            }
            return
        }

        localesDir.mkdirs()

        try {
            val assetFiles = context.assets.list("locales") ?: emptyArray()
            for (filename in assetFiles) {
                if (!filename.endsWith(".json")) continue
                context.assets.open("locales/$filename").use { input ->
                    File(localesDir, filename).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            versionFile.writeText(currentVersion)
            try {
                initLocales(localesDir.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to initialize locales: native library not found", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract locales: ${e.message}")
        }
    }

    private fun getAppVersionCode(context: Context): String =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName}-${PackageInfoCompat.getLongVersionCode(info)}"
        } catch (_: Exception) {
            "unknown"
        }

    private fun loadLocales() {
        availableLocales =
            try {
                getAvailableLocales()
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to get available locales: native library not found", e)
                emptyList()
            }
        applySelectedLocale()
    }

    /**
     * Apply the currently selected locale.
     */
    fun applySelectedLocale() {
        val p = loadPrefsOrFallback()
        followSystem = p.followSystemLanguage
        selectedLocaleCode = p.languageCode
        currentLocale =
            try {
                if (!p.followSystemLanguage && p.languageCode != null) {
                    parseLocaleCode(p.languageCode!!) ?: MobileLocale.ENGLISH
                } else {
                    val systemLanguage = Locale.getDefault().language
                    parseLocaleCode(systemLanguage) ?: MobileLocale.ENGLISH
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to apply selected locale: native library not found", e)
                MobileLocale.ENGLISH
            }
    }

    /**
     * Select a locale by code.
     */
    fun selectLocale(code: String) {
        persist(languageCode = code, followSystemLanguage = false)
        applySelectedLocale()
    }

    /**
     * Select a locale directly.
     */
    fun selectLocale(locale: MobileLocale) {
        val info =
            try {
                getLocaleInfo(locale)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to get locale info: native library not found", e)
                MobileLocaleInfo(code = "en", name = "English", englishName = "English", isRtl = false)
            }
        selectLocale(info.code)
    }

    /**
     * Reset to follow system language.
     */
    fun resetToSystem() {
        persist(languageCode = null, followSystemLanguage = true)
        applySelectedLocale()
    }

    private fun persist(
        languageCode: String?,
        followSystemLanguage: Boolean,
    ) {
        val engine = appEngine
        if (engine != null) {
            try {
                val current = engine.getAppPreferences()
                engine.setAppPreferences(
                    MobileAppPreferences(
                        themeId = current.themeId,
                        languageCode = languageCode,
                        followSystemTheme = current.followSystemTheme,
                        followSystemLanguage = followSystemLanguage,
                    ),
                )
                return
            } catch (_: Exception) {
                // Fall through to SharedPreferences-backed fallback.
            }
        }
        prefs.edit {
            putBoolean(KEY_FOLLOW_SYSTEM, followSystemLanguage)
            if (languageCode == null) remove(KEY_SELECTED_LOCALE) else putString(KEY_SELECTED_LOCALE, languageCode)
        }
    }

    /**
     * Get a localized string by key.
     */
    fun t(key: String): String =
        try {
            getString(currentLocale, key)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to get string: native library not found", e)
            key
        }

    /**
     * Get a localized string with arguments.
     */
    fun t(
        key: String,
        args: Map<String, String>,
    ): String =
        try {
            getStringWithArgs(currentLocale, key, args)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to get string with args: native library not found", e)
            key
        }

    /** Get info for the current locale */
    val currentLocaleInfo: MobileLocaleInfo
        get() =
            try {
                getLocaleInfo(currentLocale)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to get current locale info: native library not found", e)
                MobileLocaleInfo(code = "en", name = "English", englishName = "English", isRtl = false)
            }

    /** Check if current locale is RTL */
    val isRightToLeft: Boolean
        get() = currentLocaleInfo.isRtl

    companion object {
        private const val TAG = "LocalizationManager"
        private const val PREFS_NAME = "vauchi_locale_settings"
        private const val KEY_SELECTED_LOCALE = "selected_locale_code"
        private const val KEY_FOLLOW_SYSTEM = "follow_system"

        @Volatile
        private var instance: LocalizationManager? = null

        fun getInstance(context: Context): LocalizationManager =
            instance ?: synchronized(this) {
                instance ?: LocalizationManager(context.applicationContext).also { instance = it }
            }
    }
}
