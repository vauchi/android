// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the declarations that Android 16 (API 36) behaviour changes depend on.
 *
 * `targetSdk 36` opts the app into enforced edge-to-edge, predictive back, and
 * ignored orientation restrictions on large screens. The app already satisfies
 * all three — `enableEdgeToEdge()` in `MainActivity`, `BackHandler` rather than
 * `onBackPressed()`, and no orientation lock — so the risk is not that they are
 * wrong today but that each reverts *silently*: the escape hatch for every one
 * is a manifest attribute or a lowered `targetSdk`, and none of them fails a
 * build, a lint, or any existing test.
 *
 * These read the declaration files directly rather than through
 * `PackageManager`. Robolectric has no merged manifest here — the module sets
 * no `unitTests.isIncludeAndroidResources`, so `RuntimeEnvironment` reports
 * package `org.robolectric.default` and `targetSdkVersion` 23 — and launching
 * `MainActivity` is not possible on the JVM anyway, since it pulls
 * `uniffi.vauchi_platform` and its native library. The files asserted here are
 * exactly the ones a developer edits to break these contracts.
 *
 * What this cannot cover: the *visual* half of edge-to-edge — that content is
 * not obscured by the system bars — and the predictive-back gesture itself.
 * Both need the device pass tracked in
 * `2026-06-11-store-submission-blockers`.
 */
class Api36BehaviourContractTest {
    @Test
    fun target_and_compile_sdk_are_36() {
        val gradle = read("build.gradle.kts")
        // Play has refused submissions below API 36 since 2026-08-31, and the
        // two contracts below are only in force at 36. Lowering this to dodge
        // an Android 16 behaviour change closes the store path again.
        assertTrue(
            "app/build.gradle.kts must declare compileSdk = 36",
            Regex("""compileSdk\s*=\s*36""").containsMatchIn(gradle),
        )
        assertTrue(
            "app/build.gradle.kts must declare targetSdk = 36",
            Regex("""targetSdk\s*=\s*36""").containsMatchIn(gradle),
        )
    }

    @Test
    fun predictive_back_is_not_disabled() {
        // The callback defaults on at targetSdk >= 33; the only way off is this
        // attribute — the tempting fix for a back-navigation bug, which
        // silently reverts the app to legacy back and drops the predictive
        // gesture. PresentationHost drives back through BackHandler, which
        // needs the callback enabled to behave as intended.
        assertFalse(
            "android:enableOnBackInvokedCallback must not be disabled",
            read("src/main/AndroidManifest.xml")
                .contains(Regex("""enableOnBackInvokedCallback\s*=\s*"false""""))
        )
    }

    @Test
    fun main_activity_declares_no_orientation_lock() {
        // API 36 ignores orientation, resizeability and aspect-ratio
        // restrictions on large screens. A lock declared here would therefore
        // be honoured on a phone and ignored on a tablet — the form-factor
        // divergence ADR-066 forbids a frontend from encoding.
        val manifest = read("src/main/AndroidManifest.xml")
        assertFalse(
            "MainActivity must not pin android:screenOrientation",
            manifest.contains(Regex("""android:screenOrientation""")),
        )
        assertFalse(
            "MainActivity must not declare android:resizeableActivity=\"false\"",
            manifest.contains(Regex("""resizeableActivity\s*=\s*"false"""")),
        )
    }

    /**
     * Resolves a module-relative path from either the module or the repo root,
     * and fails loudly rather than letting an assertion pass vacuously when the
     * working directory is not what this test assumed.
     */
    private fun read(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        assertEquals(
            "expected to find $relative at one of " +
                candidates.joinToString { it.absolutePath },
            true,
            found != null,
        )
        return found!!.readText()
    }
}
