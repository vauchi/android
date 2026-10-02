// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The foreground heartbeat drives everything core does on a timer while the
 * app is open, including the frame a live QR exchange shows. Virtual time
 * throughout: the intervals are core's, and the loop must follow them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundHeartbeatTest {
    private fun TestScope.heartbeat(
        ticks: MutableList<Long>,
        intervals: Iterator<Long>,
    ): Pair<ForegroundHeartbeat, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val heartbeat =
            ForegroundHeartbeat(scope) {
                ticks.add(currentTime)
                intervals.next()
            }
        return heartbeat to scope
    }

    @Test
    fun `ticks at once and then after each interval the tick returns`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, listOf(30_000L, 300L, 300L, 1_000L).iterator())

            heartbeat.start()
            advanceTimeBy(30_601)

            assertEquals(listOf(0L, 30_000L, 30_300L, 30_600L), ticks)
            scope.cancel()
        }

    @Test
    fun `a second start does not add a second loop`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, generateSequence { 1_000L }.iterator())

            heartbeat.start()
            heartbeat.start()
            advanceTimeBy(2_001)

            assertEquals(listOf(0L, 1_000L, 2_000L), ticks)
            scope.cancel()
        }

    @Test
    fun `stop ends the loop and start begins it again`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, generateSequence { 1_000L }.iterator())

            heartbeat.start()
            runCurrent()
            heartbeat.stop()
            advanceTimeBy(5_000)
            assertEquals(listOf(0L), ticks)

            heartbeat.start()
            runCurrent()
            assertEquals(listOf(0L, 5_000L), ticks)
            scope.cancel()
        }
}
