// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2B (core-gui-architecture-alignment): thin wrapper around
 * [uniffi.vauchi_platform.PlatformEventListener] that forwards presentation
 * invalidations to a Kotlin callback. Unit-testable in the JVM layer
 * because it never touches the native [PlatformAppEngine]; the
 * integration wiring into [CoreAppViewModel] is exercised on device
 * via the screenshot suite.
 */
class ScreenInvalidationListenerTest {
    @Test
    fun `forwards presentation invalidation to the callback`() {
        var invocations = 0
        val listener = ScreenInvalidationListener { invocations++ }

        listener.onPresentationInvalidated()

        assertEquals(1, invocations)
    }
}
