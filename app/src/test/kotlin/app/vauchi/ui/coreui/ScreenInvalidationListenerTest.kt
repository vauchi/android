// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Phase 2B (core-gui-architecture-alignment): thin wrapper around
 * [uniffi.vauchi_platform.PlatformEventListener] that forwards screen
 * invalidations to a Kotlin callback. Unit-testable in the JVM layer
 * because it never touches the native [PlatformAppEngine]; the
 * integration wiring into [CoreAppViewModel] is exercised on device
 * via the screenshot suite.
 */
class ScreenInvalidationListenerTest {
    @Test
    fun `forwards screen ids verbatim to the callback`() {
        var received: List<String>? = null
        val listener = ScreenInvalidationListener { received = it }

        listener.onScreensInvalidated(listOf("home", "contacts"))

        assertNotNull(received)
        assertEquals(listOf("home", "contacts"), received)
    }

    @Test
    fun `tolerates an empty invalidation list`() {
        var invocations = 0
        val listener = ScreenInvalidationListener { invocations++ }

        listener.onScreensInvalidated(emptyList())

        assertEquals(1, invocations)
    }
}
