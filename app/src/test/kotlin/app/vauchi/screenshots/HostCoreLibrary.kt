// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import java.io.File

/**
 * The host build of Core's vauchi-platform library. Only the CI job that
 * builds it (and a developer who places it) runs the JVM tests that drive the
 * real engine; elsewhere they are skipped, not failed.
 */
object HostCoreLibrary {
    val dir: File = File(System.getProperty("jna.library.path") ?: "native-host-libs")

    fun present(): Boolean = dir.listFiles().orEmpty().any { it.name.startsWith("libvauchi_platform.") }
}
