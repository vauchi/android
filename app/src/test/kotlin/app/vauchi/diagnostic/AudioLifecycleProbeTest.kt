// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AudioLifecycleProbeTest {
    // @internal — native failure is not a successful cancellation measurement.
    @Test
    fun `a failed operation is inconclusive`() {
        val probe =
            AudioLifecycleProbe(
                operation = { AudioProbeOutcome(error = "device refused") },
                active = { false },
                stop = {},
            )
        val report = probe.measure(replace = false, settleMs = 0, waitMs = 500)
        assertEquals("inconclusive", report.status)
        assertEquals("device refused", report.firstError)
        assertEquals(null, report.workerReturnAfterActionMs)
    }

    // @internal — observe the operation's return, not only the active flag.
    @Test
    fun `stop measurement observes a cooperative backend returning`() {
        val running = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val probe =
            AudioLifecycleProbe(
                operation = {
                    running.set(true)
                    while (!stopped.get()) Thread.yield()
                    AudioProbeOutcome(samples = 32)
                },
                active = running::get,
                stop = {
                    running.set(false)
                    stopped.set(true)
                },
            )
        val report = probe.measure(replace = false, settleMs = 0, waitMs = 500)
        assertEquals("observed", report.status)
        assertTrue(report.initialActive)
        assertTrue(report.workerReturnAfterActionMs!! in 0.0..500.0)
        assertEquals(32, report.samples)
        assertEquals("", report.firstError)
    }
}
