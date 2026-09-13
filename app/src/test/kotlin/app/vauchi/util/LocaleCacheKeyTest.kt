// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The locale catalogue ships in the APK but is served to core from
 * `filesDir/locales`, extracted once and guarded by a recorded key. When
 * that key was the app version alone, a build that changed only the
 * catalogue reused the stale copy, and core rendered the literal text
 * `Missing: <key>` for every string added since — a store-loaded locale
 * wins hit-or-miss over the compiled-in fallback (`i18n::lookup_one`).
 */
class LocaleCacheKeyTest {
    // @internal
    @Test
    fun `an edited catalogue on the same app version re-extracts`() {
        val yesterday = localeCacheKey("0.1.3", 4, "1f3a9c0e77b21d54")
        val today = localeCacheKey("0.1.3", 4, "9b20e4aa15cc7301")

        assertTrue(
            "same version, different catalogue must re-extract",
            shouldExtractLocales(recordedKey = yesterday, currentKey = today),
        )
    }

    // @internal
    @Test
    fun `an unchanged catalogue keeps the extracted copy`() {
        val key = localeCacheKey("0.1.3", 4, "9b20e4aa15cc7301")

        assertFalse(shouldExtractLocales(recordedKey = key, currentKey = key))
        assertFalse(shouldExtractLocales(recordedKey = " $key \n", currentKey = key))
    }

    // @internal
    @Test
    fun `a first launch with no recorded key extracts`() {
        val key = localeCacheKey("0.1.3", 4, "9b20e4aa15cc7301")

        assertTrue(shouldExtractLocales(recordedKey = null, currentKey = key))
        assertTrue(shouldExtractLocales(recordedKey = "", currentKey = key))
    }

    // @internal
    @Test
    fun `the key carries version, code and digest so any of them invalidates`() {
        assertEquals("0.1.3-4-9b20e4aa15cc7301", localeCacheKey("0.1.3", 4, "9b20e4aa15cc7301"))
        assertTrue(
            shouldExtractLocales(
                recordedKey = localeCacheKey("0.1.3", 4, "same"),
                currentKey = localeCacheKey("0.1.4", 5, "same"),
            ),
        )
    }
}
