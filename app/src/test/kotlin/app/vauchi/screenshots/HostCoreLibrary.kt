// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import android.os.Build
import org.junit.Assume.assumeTrue
import org.robolectric.annotation.GraphicsMode
import org.robolectric.config.ConfigurationRegistry
import java.io.File

/**
 * The host build of Core's vauchi-platform library. Only the CI job that
 * builds it (and a developer who places it) runs the JVM tests that drive the
 * real engine; elsewhere they are skipped, not failed.
 *
 * Every such test runs in one Robolectric sandbox: `@GraphicsMode(NATIVE)` and
 * `@Config(sdk = [SDK])`. The library loads once per JVM and keeps one table of
 * Kotlin callbacks, registered by the sandbox that loaded the bindings last,
 * so a test in another sandbox calls the keychain back through classes that
 * are not its own and fails depending on test order.
 */
object HostCoreLibrary {
    const val SDK = 34

    val dir: File = File(System.getProperty("jna.library.path") ?: "native-host-libs")

    fun present(): Boolean = dir.listFiles().orEmpty().any { it.name.startsWith("libvauchi_platform.") }

    /** Skips without the library; fails a test that is not in the shared sandbox. */
    fun assumePresentInSharedSandbox() {
        assumeTrue("host vauchi-platform library missing under $dir", present())
        check(Build.VERSION.SDK_INT == SDK && ConfigurationRegistry.get(GraphicsMode.Mode::class.java) == GraphicsMode.Mode.NATIVE) {
            "tests that load the host Core library need @GraphicsMode(NATIVE) and @Config(sdk = [HostCoreLibrary.SDK])"
        }
    }
}
