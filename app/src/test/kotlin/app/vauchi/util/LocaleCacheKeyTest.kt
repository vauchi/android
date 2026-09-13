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
 * that key was the app version alone, a rebuild that changed only the
 * catalogue reused the stale copy, and core rendered the literal text
 * `Missing: <key>` for every string added since — a store-loaded locale
 * wins hit-or-miss over the compiled-in fallback (`i18n::lookup_one`).
 */
class LocaleCacheKeyTest {
    // @internal
    @Test
    fun `a rebuild of the same app version re-extracts the catalogue`() {
        val yesterday = localeCacheKey("0.1.3", 4, "20260912-131000")
        val today = localeCacheKey("0.1.3", 4, "20260913-060402")

        assertTrue(
            "same version, newer build must re-extract",
            shouldExtractLocales(recordedKey = yesterday, currentKey = today),
        )
    }

    // @internal
    @Test
    fun `an unchanged build keeps the extracted catalogue`() {
        val key = localeCacheKey("0.1.3", 4, "20260913-060402")

        assertFalse(shouldExtractLocales(recordedKey = key, currentKey = key))
        assertFalse(shouldExtractLocales(recordedKey = " $key \n", currentKey = key))
    }

    // @internal
    @Test
    fun `a first launch with no recorded key extracts`() {
        val key = localeCacheKey("0.1.3", 4, "20260913-060402")

        assertTrue(shouldExtractLocales(recordedKey = null, currentKey = key))
        assertTrue(shouldExtractLocales(recordedKey = "", currentKey = key))
    }

    // @internal
    @Test
    fun `the key carries version, code and build so any of them invalidates`() {
        assertEquals("0.1.3-4-20260913-060402", localeCacheKey("0.1.3", 4, "20260913-060402"))
        assertTrue(
            shouldExtractLocales(
                recordedKey = localeCacheKey("0.1.3", 4, "b"),
                currentKey = localeCacheKey("0.1.4", 5, "b"),
            ),
        )
    }
}
