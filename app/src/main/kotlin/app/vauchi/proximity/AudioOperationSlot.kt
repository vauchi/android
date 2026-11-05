// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AudioOperationSlot {
    internal class Operation(
        private val stop: () -> Unit,
    ) {
        private val cancelled = CountDownLatch(1)
        val isActive: Boolean get() = cancelled.count != 0L

        fun await(timeoutMs: Long) {
            cancelled.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }

        fun cancel() {
            cancelled.countDown()
            stop()
        }
    }

    private val lock = ReentrantLock()
    private var current: Operation? = null
    val isActive: Boolean get() = lock.withLock { current != null }

    fun <T> run(
        start: () -> Unit,
        stop: () -> Unit,
        work: (Operation) -> T,
    ): T {
        val operation =
            lock.withLock {
                cancelCurrent()
                val next = Operation(stop)
                current = next
                try {
                    start()
                } catch (error: Exception) {
                    cancelCurrent()
                    throw error
                }
                next
            }
        try {
            return work(operation)
        } finally {
            lock.withLock { if (current === operation) cancelCurrent() }
        }
    }

    fun stop() = lock.withLock { cancelCurrent() }

    private fun cancelCurrent() {
        val operation = current ?: return
        current = null
        operation.cancel()
    }
}
