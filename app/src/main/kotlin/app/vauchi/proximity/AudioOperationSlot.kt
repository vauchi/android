// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AudioOperationSlot {
    internal class Request

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

        fun interrupt() {
            cancelled.countDown()
        }
    }

    private val lock = ReentrantLock()
    private var current: Operation? = null
    private var latest: Request? = null
    val isActive: Boolean get() = lock.withLock { current != null }

    fun reserve(): Request =
        lock.withLock {
            val request = Request()
            latest = request
            current?.interrupt()
            request
        }

    fun deliver(
        request: Request,
        body: () -> Unit,
    ): Boolean =
        lock.withLock {
            if (latest !== request) return false
            body()
            true
        }

    fun <T> run(
        request: Request? = null,
        start: () -> Unit,
        stop: () -> Unit,
        work: (Operation) -> T,
    ): T? {
        val operation =
            lock.withLock {
                val desired = request ?: reserve()
                if (latest !== desired) return null
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

    fun stop() =
        lock.withLock {
            latest = null
            cancelCurrent()
        }

    private fun cancelCurrent() {
        val operation = current ?: return
        current = null
        operation.cancel()
    }
}
