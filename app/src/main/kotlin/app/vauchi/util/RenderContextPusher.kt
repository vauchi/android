// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.util.Log
import uniffi.vauchi_platform.PlatformAppEngine
import java.util.Locale

/**
 * Push the union of [ThemeManager] and [LocalizationManager] state to
 * core's `RenderContext` via `setRenderContextJson`.
 *
 * Each manager is OS-native canonical for its own slice of state
 * (theme / locale) after S4 of the
 * `2026-05-16-settings-storage-by-sensitivity` plan. Core needs to
 * read those values to render the Settings dropdown's `selected`
 * value and to drive locale-aware string lookup. The push is the
 * single wire across the boundary.
 *
 * Because `RenderContext` carries both fields as a unit and a JSON
 * push replaces the whole context, every push must include both
 * effective values. This function reads from both managers and
 * pushes the union — call it from either manager after persisting a
 * change.
 *
 * The OS language goes along on every push as `system_locale`: on
 * "Follow system" no `locale` is sent, and core renders the system
 * language only if it is told what it is (vauchi/private#721).
 */
internal fun pushRenderContext(
    context: Context,
    engine: PlatformAppEngine?,
) {
    if (engine == null) return
    val themeManager = ThemeManager.getInstance(context)
    val localeManager = LocalizationManager.getInstance(context)
    val effectiveTheme =
        if (themeManager.followSystem.value) null else themeManager.selectedThemeId.value
    val effectiveLocale =
        if (localeManager.followSystem) null else localeManager.selectedLocaleCode
    val json =
        buildRenderContextJson(effectiveTheme, effectiveLocale, Locale.getDefault().toLanguageTag())
    try {
        engine.setRenderContextJson(json)
    } catch (e: Exception) {
        Log.e("Vauchi", "[RenderContextPusher] Failed: ${e.javaClass.simpleName}")
    }
}

internal fun buildRenderContextJson(
    themeId: String?,
    locale: String?,
    systemLocale: String,
): String {
    val parts =
        buildList {
            if (themeId != null) add("\"theme_id\":${jsonString(themeId)}")
            if (locale != null) add("\"locale\":${jsonString(locale)}")
            add("\"system_locale\":${jsonString(systemLocale)}")
        }
    return "{" + parts.joinToString(",") + "}"
}

private fun jsonString(s: String): String {
    val escaped =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    return "\"$escaped\""
}
