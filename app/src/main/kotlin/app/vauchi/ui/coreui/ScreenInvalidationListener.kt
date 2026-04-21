// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import uniffi.vauchi_platform.PlatformEventListener

/**
 * Phase 2B (core-gui-architecture-alignment): thin adapter between
 * core's [PlatformEventListener] callback interface and a Kotlin
 * lambda. Kept out of [CoreAppViewModel] so it is constructible and
 * testable in JVM unit tests without a real [uniffi.vauchi_platform
 * .PlatformAppEngine] (which requires the native UniFFI library
 * — only loadable on device).
 *
 * Core invokes [onScreensInvalidated] from whatever thread dispatched
 * the underlying event. The caller supplying [onInvalidated] is
 * responsible for marshalling to a safe context (e.g. launching into
 * `viewModelScope`) before touching the engine — UniFFI's Mutex
 * deadlocks if the callback re-enters the engine on the same stack.
 */
class ScreenInvalidationListener(
    private val onInvalidated: (List<String>) -> Unit,
) : PlatformEventListener {
    override fun onScreensInvalidated(screenIds: List<String>) {
        onInvalidated(screenIds)
    }
}
