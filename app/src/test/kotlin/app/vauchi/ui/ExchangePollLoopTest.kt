// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for [pollLoop] — the cadence/lifecycle driver behind the
 * multi-stage exchange screen's tick. Extracted from the composable so
 * it is testable without a `PlatformAppEngine` (a concrete UniFFI type
 * that cannot be constructed in JVM tests — same reason
 * `ScreenInvalidationListener` was extracted). Regression guard for
 * Bug 5 of `2026-05-30-exchange-screen-nav-visual-bugs`: the retired
 * core cycle thread (slice-32m T1.2c) left the exchange screen with no
 * driver, so the own-QR never appeared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExchangePollLoopTest {
    @Test
    fun `ticks immediately on entry then once per interval`() =
        runTest {
            var ticks = 0
            val job = launch { pollLoop(intervalMs = 100L) { ticks++ } }

            // First tick fires before any delay — the QR must appear on entry,
            // not one interval later.
            runCurrent()
            assertEquals(1, ticks)

            advanceTimeBy(100L)
            runCurrent()
            assertEquals(2, ticks)

            // Two more intervals elapse (200ms, 300ms marks) → +2 ticks.
            advanceTimeBy(250L)
            runCurrent()
            assertEquals(4, ticks)

            job.cancelAndJoin()
        }

    @Test
    fun `stops ticking after cancellation`() =
        runTest {
            var ticks = 0
            val job = launch { pollLoop(intervalMs = 100L) { ticks++ } }
            runCurrent()
            job.cancelAndJoin()

            val frozen = ticks
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(frozen, ticks)
        }
}
