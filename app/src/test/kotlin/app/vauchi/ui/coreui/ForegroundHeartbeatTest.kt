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

    /**
     * An exchange opened during the idle sleep must not wait it out: its QR
     * stays frozen on the first frame until the loop ticks. Device-measured
     * as a first tick 27.5-29.8 s after "Exchange started" in 8 of 15 Hover
     * runs (2026-10-02, Pixel 3a,
     * `2026-10-02-exchange-first-tick-waits-for-idle-heartbeat`).
     */
    @Test
    fun `a reschedule during a long sleep brings the next tick forward`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, listOf(30_000L, 300L, 300L).iterator())

            heartbeat.start()
            advanceTimeBy(5_000)
            heartbeat.reschedule(300)
            advanceTimeBy(601)

            assertEquals(listOf(0L, 5_300L, 5_600L), ticks)
            scope.cancel()
        }

    @Test
    fun `a reschedule later than the pending tick does not delay it`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, generateSequence { 300L }.iterator())

            heartbeat.start()
            advanceTimeBy(100)
            heartbeat.reschedule(30_000)
            advanceTimeBy(501)

            assertEquals(listOf(0L, 300L, 600L), ticks)
            scope.cancel()
        }

    @Test
    fun `a reschedule from inside the tick does not add a tick`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            lateinit var heartbeat: ForegroundHeartbeat
            heartbeat =
                ForegroundHeartbeat(scope) {
                    ticks.add(currentTime)
                    heartbeat.reschedule(300)
                    300L
                }

            heartbeat.start()
            advanceTimeBy(901)

            assertEquals(listOf(0L, 300L, 600L, 900L), ticks)
            scope.cancel()
        }

    @Test
    fun `a reschedule while stopped does not shorten the first sleep after start`() =
        runTest {
            val ticks = mutableListOf<Long>()
            val (heartbeat, scope) = heartbeat(ticks, generateSequence { 30_000L }.iterator())

            heartbeat.reschedule(300)
            advanceTimeBy(1_000)
            assertEquals(emptyList<Long>(), ticks)

            heartbeat.start()
            advanceTimeBy(30_001)

            assertEquals(listOf(1_000L, 31_000L), ticks)
            scope.cancel()
        }
}
