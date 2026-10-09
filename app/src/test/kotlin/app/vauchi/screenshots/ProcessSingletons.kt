// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import androidx.lifecycle.ViewModelProvider
import app.vauchi.data.VauchiRepository
import app.vauchi.util.LocalizationManager
import app.vauchi.util.ThemeManager

/**
 * The app's process singletons outlive a Robolectric test's application
 * instance; clearing them gives each test a fresh engine on its own data
 * directory.
 */
object ProcessSingletons {
    fun reset() {
        listOf(VauchiRepository::class.java, LocalizationManager::class.java, ThemeManager::class.java).forEach { cls ->
            cls.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        // The default factory keeps the first test's Application, so a later
        // test's MainViewModel would open the first test's data directory,
        // whose keychain files that test's fake keys no longer open.
        ViewModelProvider.AndroidViewModelFactory::class.java
            .getDeclaredField("_instance")
            .apply { isAccessible = true }
            .set(null, null)
    }

    /** Makes [repository] the one the app's code gets. */
    fun useRepository(repository: VauchiRepository) {
        VauchiRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, repository)
    }
}
