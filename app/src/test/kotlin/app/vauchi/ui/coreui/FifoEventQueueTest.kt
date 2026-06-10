// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hardware events must reach core strictly in arrival order: BLE/ATT
 * delivers notifications in order, and core's handshake machine relies
 * on it (the KeyAck must precede the card chunks it authenticates).
 * Dispatching each event on a pooled dispatcher re-orders events that
 * arrive milliseconds apart — observed as "No pending KeyAck data" on
 * the Magic-mode initiator. See
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
class FifoEventQueueTest {
    @Test
    fun `processes events in submission order even when a handler suspends`() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val processed = mutableListOf<Int>()
            val queue =
                FifoEventQueue<Int>(scope) { event ->
                    // First event simulates a slow core round-trip.
                    if (event == 1) delay(100)
                    processed.add(event)
                }

            queue.send(1)
            queue.send(2)
            queue.send(3)
            advanceUntilIdle()
            scope.cancel()

            assertEquals(listOf(1, 2, 3), processed)
        }

    @Test
    fun `never processes two events concurrently`() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            var inFlight = 0
            var maxInFlight = 0
            val queue =
                FifoEventQueue<Int>(scope) {
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    delay(10)
                    inFlight--
                }

            repeat(5) { queue.send(it) }
            advanceUntilIdle()
            scope.cancel()

            assertEquals(1, maxInFlight)
        }
}
