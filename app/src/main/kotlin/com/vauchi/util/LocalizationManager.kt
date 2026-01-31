// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import uniffi.vauchi_mobile.MobileLocale
import uniffi.vauchi_mobile.MobileLocaleInfo
import uniffi.vauchi_mobile.getAvailableLocales
import uniffi.vauchi_mobile.getLocaleInfo
import uniffi.vauchi_mobile.getString
import uniffi.vauchi_mobile.getStringWithArgs
import uniffi.vauchi_mobile.parseLocaleCode
import java.util.Locale

/**
 * Manages localization/internationalization.
 * Integrates with vauchi-mobile for string translations.
 */
class LocalizationManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Currently selected locale */
    var currentLocale: MobileLocale by mutableStateOf(MobileLocale.ENGLISH)
        private set

    /** All available locales */
    var availableLocales: List<MobileLocaleInfo> by mutableStateOf(emptyList())
        private set

    /** Whether to follow system language */
    var followSystem: Boolean by mutableStateOf(true)
        private set

    /** Selected locale code */
    var selectedLocaleCode: String?
        get() = prefs.getString(KEY_SELECTED_LOCALE, null)
        private set(value) {
            prefs.edit { putString(KEY_SELECTED_LOCALE, value) }
        }

    init {
        followSystem = prefs.getBoolean(KEY_FOLLOW_SYSTEM, true)
        loadLocales()
    }

    private fun loadLocales() {
        availableLocales = getAvailableLocales()
        applySelectedLocale()
    }

    /**
     * Apply the currently selected locale.
     */
    fun applySelectedLocale() {
        currentLocale = if (!followSystem && selectedLocaleCode != null) {
            parseLocaleCode(selectedLocaleCode!!) ?: MobileLocale.ENGLISH
        } else {
            // Use system language
            val systemLanguage = Locale.getDefault().language
            parseLocaleCode(systemLanguage) ?: MobileLocale.ENGLISH
        }
    }

    /**
     * Select a locale by code.
     */
    fun selectLocale(code: String) {
        followSystem = false
        prefs.edit { putBoolean(KEY_FOLLOW_SYSTEM, false) }
        selectedLocaleCode = code
        applySelectedLocale()
    }

    /**
     * Select a locale directly.
     */
    fun selectLocale(locale: MobileLocale) {
        val info = getLocaleInfo(locale)
        selectLocale(info.code)
    }

    /**
     * Reset to follow system language.
     */
    fun resetToSystem() {
        followSystem = true
        prefs.edit { putBoolean(KEY_FOLLOW_SYSTEM, true) }
        selectedLocaleCode = null
        applySelectedLocale()
    }

    /**
     * Get a localized string by key.
     */
    fun t(key: String): String {
        return getString(currentLocale, key)
    }

    /**
     * Get a localized string with arguments.
     */
    fun t(key: String, args: Map<String, String>): String {
        return getStringWithArgs(currentLocale, key, args)
    }

    /** Get info for the current locale */
    val currentLocaleInfo: MobileLocaleInfo
        get() = getLocaleInfo(currentLocale)

    /** Check if current locale is RTL */
    val isRightToLeft: Boolean
        get() = currentLocaleInfo.isRtl

    companion object {
        private const val PREFS_NAME = "vauchi_locale_settings"
        private const val KEY_SELECTED_LOCALE = "selected_locale_code"
        private const val KEY_FOLLOW_SYSTEM = "follow_system"

        @Volatile
        private var instance: LocalizationManager? = null

        fun getInstance(context: Context): LocalizationManager {
            return instance ?: synchronized(this) {
                instance ?: LocalizationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
