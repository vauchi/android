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
 *
 * Source of truth is the `vauchi_locale_settings` SharedPreferences
 * store (OS-native, Category 1 — render-context). Core's
 * `RenderContext` is informed of changes via `setRenderContextJson`
 * so the Settings dropdown's `selected` value and locale-aware
 * string lookup stay in sync (S4 of
 * `2026-05-16-settings-storage-by-sensitivity`).
 */
class LocalizationManager(
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        extractAndInitLocales(appContext)
        loadLocales()
    }

    /**
     * Wire this manager to the live [PlatformAppEngine] instance so
     * subsequent locale changes propagate to core's `RenderContext`.
     * Called once by `VauchiRepository.ensureInitialized()` after the platform
     * finishes lazy initialisation. Re-applies the locale and pushes
     * it to core so the Settings dropdown reflects what's on disk.
     */
    fun attachAppEngine(appEngine: PlatformAppEngine) {
        this.appEngine = appEngine
        applySelectedLocale()
        pushRenderContext(appContext, appEngine)

        // The engine's first screen may have been rendered with the bundled
        // fallback catalog before locales were extracted and initialized.
        // Force a full rebuild now that the catalog is loaded and the render
        // context has been pushed, so the onboarding/first screen reflects
        // the real translations instead of "Missing: ..." placeholders.
        try {
            appEngine.invalidateAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate engine after locale attach: ${e.javaClass.simpleName}", e)
        }
    }

    /**
     * Extract locale JSON files from assets to internal storage and
     * initialize the core i18n system. Runs once per install/update.
     */
    private fun extractAndInitLocales(context: Context) {
        val localesDir = File(context.filesDir, "locales")
        val versionFile = File(localesDir, ".version")
        val currentVersion = getAppVersionCode(context)

        // Skip extraction if already done for this app version
        if (versionFile.exists() && versionFile.readText().trim() == currentVersion) {
            try {
                initLocales(localesDir.absolutePath)
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to initialize locales: native library not loaded", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize locales from existing files: ${e.message}", e)
            }
            return
        }

        localesDir.mkdirs()

        try {
            val assetFiles = listLocaleAssetFiles(context)
            for (filename in assetFiles) {
                context.assets.open(filename).use { input ->
                    File(localesDir, File(filename).name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            versionFile.writeText(currentVersion)
            try {
                initLocales(localesDir.absolutePath)
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to initialize locales: native library not loaded", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize locales: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract locales: ${e.message}", e)
        }
    }

    /**
     * Discover locale JSON files packaged as assets.
     *
     * First tries the canonical `locales/` sub-directory. If that is empty,
     * falls back to the asset root and selects files that look like locale
     * catalogs (e.g. `en.json`, `de-CH.json`). The fallback supports wiring
     * an external `locales/` directory directly via `assets.srcDir` without
     * an intermediate copy step.
     */
    private fun listLocaleAssetFiles(context: Context): List<String> {
        val canonical = context.assets.list("locales")?.filter { it.endsWith(".json") }
        if (!canonical.isNullOrEmpty()) {
            return canonical.map { "locales/$it" }
        }

        val root = context.assets.list("") ?: emptyArray()
        val localePattern = Regex("^[a-z]{2}(-[A-Z]{2})?\\.json$")
        return root
            .filter { it.matches(localePattern) }
            .map { it }
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
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to get available locales: native library not loaded", e)
                emptyList()
            }
        applySelectedLocale()
    }

    /**
     * Apply the currently selected locale.
     */
    fun applySelectedLocale() {
        followSystem = safeGetBoolean(KEY_FOLLOW_SYSTEM, true)
        selectedLocaleCode = safeGetString(KEY_SELECTED_LOCALE, null)
        currentLocale =
            try {
                val explicit = selectedLocaleCode
                if (!followSystem && explicit != null) {
                    parseLocaleCode(explicit) ?: MobileLocale.ENGLISH
                } else {
                    val systemLanguage = Locale.getDefault().language
                    parseLocaleCode(systemLanguage) ?: MobileLocale.ENGLISH
                }
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to apply selected locale: native library not loaded", e)
                MobileLocale.ENGLISH
            }
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
     * Select a locale by code.
     */
    fun selectLocale(code: String) {
        persist(languageCode = code, followSystemLanguage = false)
        applySelectedLocale()
        pushRenderContext(appContext, appEngine)
    }

    /**
     * Select a locale directly.
     */
    fun selectLocale(locale: MobileLocale) {
        val info =
            try {
                getLocaleInfo(locale)
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to get locale info: native library not loaded", e)
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
        pushRenderContext(appContext, appEngine)
    }

    private fun persist(
        languageCode: String?,
        followSystemLanguage: Boolean,
    ) {
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
        } catch (e: LinkageError) {
            Log.e(TAG, "Failed to get string: native library not loaded", e)
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
        } catch (e: LinkageError) {
            Log.e(TAG, "Failed to get string with args: native library not loaded", e)
            key
        }

    /** Get info for the current locale */
    val currentLocaleInfo: MobileLocaleInfo
        get() =
            try {
                getLocaleInfo(currentLocale)
            } catch (e: LinkageError) {
                Log.e(TAG, "Failed to get current locale info: native library not loaded", e)
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
