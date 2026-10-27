// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the JSON [buildRenderContextJson] sends to core's
 * `setRenderContextJson`. On "Follow system" no `locale` is sent, so core
 * rendered English until the OS language travelled as `system_locale`
 * (vauchi/private#721).
 *
 * Uses Robolectric only for `org.json.JSONObject`.
 */
@RunWith(RobolectricTestRunner::class)
class RenderContextPusherTest {
    @Test
    fun `following the system sends the system language and no picked language`() {
        val json = JSONObject(buildRenderContextJson(locale = null, systemLocale = "fr-CH"))

        assertEquals("fr-CH", json.getString("system_locale"))
        assertFalse(json.has("locale"))
        assertFalse(json.has("theme_id"))
    }

    @Test
    fun `a picked language still travels with the system language`() {
        val json = JSONObject(buildRenderContextJson(locale = "de", systemLocale = "fr-CH"))

        assertEquals("de", json.getString("locale"))
        assertEquals("fr-CH", json.getString("system_locale"))
        assertFalse(json.has("theme_id"))
    }

    @Test
    fun `a quote or backslash in the system tag stays inside its string`() {
        val json = JSONObject(buildRenderContextJson(locale = null, systemLocale = "x\"\\y"))

        assertEquals("x\"\\y", json.getString("system_locale"))
    }
}
