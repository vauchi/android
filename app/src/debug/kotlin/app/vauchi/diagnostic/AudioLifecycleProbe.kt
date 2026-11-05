// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal data class AudioProbeOutcome(
    val error: String = "",
    val samples: Int? = null,
)

internal data class AudioProbeReport(
    val status: String,
    val initialActive: Boolean,
    val actionReturnMs: Double?,
    val workerReturnAfterActionMs: Double?,
    val backendCallOverlap: Boolean?,
    val activeWhenOldReturned: Boolean?,
    val replacementPendingWhenOldReturned: Boolean?,
    val firstError: String?,
    val secondError: String?,
    val samples: Int?,
)

internal class AudioLifecycleProbe(
    private val operation: (Long) -> AudioProbeOutcome,
    private val active: () -> Boolean,
    private val stop: () -> Unit,
) {
    private data class Returned(
        val time: Long,
        val outcome: AudioProbeOutcome,
    )

    private class Call {
        val entered = AtomicLong(0)
        val returned = AtomicReference<Returned?>()
    }

    private fun launch(durationMs: Long): Call {
        val call = Call()
        Thread({
            call.entered.set(System.nanoTime())
            val outcome =
                try {
                    operation(durationMs)
                } catch (error: Exception) {
                    AudioProbeOutcome(error = error.toString())
                }
            call.returned.set(Returned(System.nanoTime(), outcome))
        }, "audio-lifecycle-probe").apply {
            isDaemon = true
            start()
        }
        return call
    }

    private fun await(
        timeoutMs: Long,
        ready: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!ready() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000)
        }
        return ready()
    }

    fun measure(
        replace: Boolean,
        settleMs: Long = 250,
        waitMs: Long = 5_000,
    ): AudioProbeReport {
        val first = launch(2_000)
        await(waitMs) { active() || first.returned.get() != null }
        await(settleMs) { first.returned.get() != null }
        val initialActive = active() && first.returned.get() == null
        val actionEntry = System.nanoTime()
        var actionReturn: Long? = null
        var second: Call? = null
        var activeAtOldReturn: Boolean? = null
        var secondPending: Boolean? = null
        var firstObserved: Returned? = null
        var secondObserved: Returned? = null
        try {
            if (initialActive) {
                if (replace) {
                    second = launch(3_000)
                } else {
                    stop()
                    actionReturn = System.nanoTime()
                }
                await(waitMs) { first.returned.get() != null }
                firstObserved = first.returned.get()
                if (replace && firstObserved != null) {
                    activeAtOldReturn = active()
                    secondPending = second?.returned?.get() == null
                }
                second?.let { call ->
                    await(waitMs) { call.returned.get() != null }
                    secondObserved = call.returned.get()
                }
            } else {
                firstObserved = first.returned.get()
            }
        } finally {
            stop()
        }
        val overlap =
            second?.entered?.get()?.takeIf { it != 0L }?.let { entered ->
                firstObserved?.let { entered < it.time } ?: true
            }
        val observed =
            initialActive && firstObserved != null &&
                firstObserved.outcome.error.isEmpty() &&
                (!replace || secondObserved?.outcome?.error == "")
        return AudioProbeReport(
            status = if (observed) "observed" else "inconclusive",
            initialActive = initialActive,
            actionReturnMs = actionReturn?.let { (it - actionEntry) / 1_000_000.0 },
            workerReturnAfterActionMs =
                if (initialActive) {
                    firstObserved?.let { (it.time - actionEntry) / 1_000_000.0 }
                } else {
                    null
                },
            backendCallOverlap = overlap,
            activeWhenOldReturned = activeAtOldReturn,
            replacementPendingWhenOldReturned = secondPending,
            firstError = firstObserved?.outcome?.error,
            secondError = secondObserved?.outcome?.error,
            samples = firstObserved?.outcome?.samples,
        )
    }
}
