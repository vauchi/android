// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pin the SharedPreferences-canonical contract for [ThemeManager]
 * introduced by S4 of the `2026-05-16-settings-storage-by-sensitivity`
 * plan: the manager no longer consults
 * `PlatformAppEngine.getAppPreferences()` for reads —
 * `vauchi_theme_settings` SharedPreferences is the only source of
 * truth. The render-context push to core happens through a separate
 * code path and is not exercised here (instrumentation coverage).
 */
@RunWith(RobolectricTestRunner::class)
class ThemeManagerSharedPrefsTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences("vauchi_theme_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun newManager(): ThemeManager = ThemeManager(RuntimeEnvironment.getApplication())

    @Test
    fun `defaults follow system when SharedPreferences empty`() {
        val mgr = newManager()
        assertTrue("default followSystem is true", mgr.followSystem.value)
        assertNull("default selectedThemeId is null", mgr.selectedThemeId.value)
    }

    @Test
    fun `reads explicit theme from SharedPreferences on init`() {
        prefs
            .edit()
            .putString("selected_theme_id", "cyber")
            .putBoolean("follow_system", false)
            .commit()

        val mgr = newManager()

        assertFalse("followSystem reflects stored false", mgr.followSystem.value)
        assertEquals(
            "selectedThemeId matches stored value",
            "cyber",
            mgr.selectedThemeId.value,
        )
    }

    @Test
    fun `selectTheme persists themeId and clears follow_system`() {
        val mgr = newManager()

        mgr.selectTheme("cyber", isDarkMode = false)

        assertEquals(
            "selected_theme_id persisted",
            "cyber",
            prefs.getString("selected_theme_id", null),
        )
        assertFalse("follow_system flipped to false", prefs.getBoolean("follow_system", true))
        assertFalse("manager state reflects persisted pick", mgr.followSystem.value)
        assertEquals(
            "manager state reflects persisted pick",
            "cyber",
            mgr.selectedThemeId.value,
        )
    }

    @Test
    fun `resetToSystem clears explicit theme and sets follow_system`() {
        prefs
            .edit()
            .putString("selected_theme_id", "cyber")
            .putBoolean("follow_system", false)
            .commit()
        val mgr = newManager()

        mgr.resetToSystem(isDarkMode = false)

        assertTrue("follow_system restored", prefs.getBoolean("follow_system", false))
        assertFalse(
            "selected_theme_id removed",
            prefs.contains("selected_theme_id"),
        )
        assertTrue("manager state reflects reset", mgr.followSystem.value)
        assertNull("manager state reflects reset", mgr.selectedThemeId.value)
    }

    @Test
    fun `corrupted boolean falls back to default without crash`() {
        prefs.edit().putString("follow_system", "not-a-boolean").commit()

        val mgr = newManager()

        assertTrue("falls back to follow_system=true on corruption", mgr.followSystem.value)
    }

    @Test
    fun `corrupted string falls back to null without crash`() {
        prefs.edit().putBoolean("selected_theme_id", true).commit()

        val mgr = newManager()

        assertNull("falls back to null themeId on corruption", mgr.selectedThemeId.value)
    }
}
