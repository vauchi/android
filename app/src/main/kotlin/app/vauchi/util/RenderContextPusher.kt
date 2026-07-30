// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.util.Log
import uniffi.vauchi_platform.PlatformAppEngine

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
    val json = buildRenderContextJson(effectiveTheme, effectiveLocale)
    try {
        engine.setRenderContextJson(json)
    } catch (e: Exception) {
        Log.e("Vauchi", "[RenderContextPusher] Failed: ${e.javaClass.simpleName}")
    }
}

private fun buildRenderContextJson(
    themeId: String?,
    locale: String?,
): String {
    val parts =
        buildList {
            if (themeId != null) add("\"theme_id\":${jsonString(themeId)}")
            if (locale != null) add("\"locale\":${jsonString(locale)}")
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
