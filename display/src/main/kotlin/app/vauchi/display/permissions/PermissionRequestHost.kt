// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

/**
 * Calls and callbacks run on Android's main owner. Closing a lease fences
 * delivery; the OS dialog remains reserved until its reply or host destruction.
 * Assembly supplies one host per Activity.
 */
fun interface PermissionRequestHost {
    fun request(
        permissions: List<String>,
        onFinished: () -> Unit,
        onUnavailable: () -> Unit,
    ): AutoCloseable
}
