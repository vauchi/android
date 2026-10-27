// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.util.Log
import uniffi.vauchi_platform.PlatformAppEngine
import java.util.Locale

/**
 * Push [LocalizationManager]'s locale to core's `RenderContext` via
 * `setRenderContextJson`. Core keeps the theme pick itself, in its data
 * directory (vauchi/private#814).
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
    val localeManager = LocalizationManager.getInstance(context)
    val effectiveLocale =
        if (localeManager.followSystem) null else localeManager.selectedLocaleCode
    val json =
        buildRenderContextJson(effectiveLocale, Locale.getDefault().toLanguageTag())
    try {
        engine.setRenderContextJson(json)
    } catch (e: Exception) {
        Log.e("Vauchi", "[RenderContextPusher] Failed: ${e.javaClass.simpleName}")
    }
}

internal fun buildRenderContextJson(
    locale: String?,
    systemLocale: String,
): String {
    val parts =
        buildList {
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
