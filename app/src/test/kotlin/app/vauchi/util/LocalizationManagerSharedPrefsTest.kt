// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.content.SharedPreferences
import app.vauchi.ui.coreui.UserAction
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
 * Pin the SharedPreferences-canonical contract for [LocalizationManager]
 * introduced by S4 of the `2026-05-16-settings-storage-by-sensitivity`
 * plan: the manager no longer consults `PlatformAppEngine.getAppPreferences()`
 * for reads — `vauchi_locale_settings` SharedPreferences is the only
 * source of truth. The render-context push to core happens through a
 * separate code path and is not exercised here (instrumentation
 * coverage).
 */
@RunWith(RobolectricTestRunner::class)
class LocalizationManagerSharedPrefsTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        // Robolectric creates a new Application per test, but
        // LocalizationManager is a JVM singleton. Reset it so each test
        // reads from and writes to the current test's SharedPreferences
        // instead of a stale application context's copy.
        LocalizationManager::class
            .java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)

        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences("vauchi_locale_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun newManager(): LocalizationManager = LocalizationManager(RuntimeEnvironment.getApplication())

    @Test
    fun `defaults follow system when SharedPreferences empty`() {
        val mgr = newManager()
        assertTrue("default followSystem is true", mgr.followSystem)
        assertNull("default selectedLocaleCode is null", mgr.selectedLocaleCode)
    }

    @Test
    fun `reads explicit locale from SharedPreferences on init`() {
        prefs
            .edit()
            .putString("selected_locale_code", "de")
            .putBoolean("follow_system", false)
            .commit()

        val mgr = newManager()

        assertFalse("followSystem reflects stored false", mgr.followSystem)
        assertEquals("selectedLocaleCode matches stored value", "de", mgr.selectedLocaleCode)
    }

    @Test
    fun `selectLocale persists code and clears follow_system`() {
        val mgr = newManager()

        mgr.selectLocale("fr")

        assertEquals(
            "selected_locale_code persisted",
            "fr",
            prefs.getString("selected_locale_code", null),
        )
        assertFalse("follow_system flipped to false", prefs.getBoolean("follow_system", true))
        assertFalse("manager state reflects persisted pick", mgr.followSystem)
        assertEquals("manager state reflects persisted pick", "fr", mgr.selectedLocaleCode)
    }

    @Test
    fun `resetToSystem clears explicit locale and sets follow_system`() {
        prefs
            .edit()
            .putString("selected_locale_code", "de")
            .putBoolean("follow_system", false)
            .commit()
        val mgr = newManager()

        mgr.resetToSystem()

        assertTrue("follow_system restored", prefs.getBoolean("follow_system", false))
        assertFalse(
            "selected_locale_code removed",
            prefs.contains("selected_locale_code"),
        )
        assertTrue("manager state reflects reset", mgr.followSystem)
        assertNull("manager state reflects reset", mgr.selectedLocaleCode)
    }

    @Test
    fun `corrupted boolean falls back to default without crash`() {
        // Inject a string where a boolean is expected — simulates a
        // SharedPreferences file rewritten by a buggy build or an
        // adversarial user-mod. Construction must not throw.
        prefs.edit().putString("follow_system", "not-a-boolean").commit()

        val mgr = newManager()

        assertTrue("falls back to follow_system=true on corruption", mgr.followSystem)
    }

    @Test
    fun `corrupted string falls back to null without crash`() {
        prefs.edit().putBoolean("selected_locale_code", true).commit()

        val mgr = newManager()

        assertNull("falls back to null locale on corruption", mgr.selectedLocaleCode)
    }

    private fun singletonManager(): LocalizationManager =
        LocalizationManager.getInstance(RuntimeEnvironment.getApplication())

    @Test
    fun `applyLocaleFromUserAction persists explicit language pick`() {
        val mgr = singletonManager()

        applyLocaleFromUserAction(
            RuntimeEnvironment.getApplication(),
            UserAction.ListItemSelected(componentId = "language", itemId = "fr"),
        )

        assertEquals("fr", prefs.getString("selected_locale_code", null))
        assertFalse(prefs.getBoolean("follow_system", true))
        assertEquals("fr", mgr.selectedLocaleCode)
    }

    @Test
    fun `applyLocaleFromUserAction resets to system for follow_system`() {
        prefs
            .edit()
            .putString("selected_locale_code", "de")
            .putBoolean("follow_system", false)
            .commit()
        val mgr = singletonManager()

        applyLocaleFromUserAction(
            RuntimeEnvironment.getApplication(),
            UserAction.ListItemSelected(componentId = "language", itemId = "follow_system"),
        )

        assertTrue(prefs.getBoolean("follow_system", false))
        assertFalse(prefs.contains("selected_locale_code"))
        assertTrue(mgr.followSystem)
    }

    @Test
    fun `applyLocaleFromUserAction ignores non language selections`() {
        applyLocaleFromUserAction(
            RuntimeEnvironment.getApplication(),
            UserAction.ListItemSelected(componentId = "theme", itemId = "dark"),
        )

        assertTrue(prefs.getBoolean("follow_system", true))
        assertFalse(prefs.contains("selected_locale_code"))
    }
}
