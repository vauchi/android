// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AudioOperationSlotTest {
    // Feature: contact_exchange.feature — native audio resource lifetime.
    @Test
    fun `stop releases resource and wakes worker`() {
        val slot = AudioOperationSlot()
        val owner = AtomicInteger(0)
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val worker =
            Thread {
                try {
                    slot.run(
                        start = {
                            owner.set(1)
                            started.countDown()
                        },
                        stop = { owner.set(0) },
                    ) { it.await(60_000) }
                } catch (failure: Throwable) {
                    error.set(failure)
                }
                returned.countDown()
            }
        worker.isDaemon = true
        worker.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            slot.stop()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(null, error.get())
            assertEquals(0, owner.get())
            assertFalse(slot.isActive)
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `replacement releases previous resource and survives its return`() {
        val slot = AudioOperationSlot()
        val owner = AtomicInteger(0)
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementReturned = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()

        fun launch(
            identity: Int,
            ready: CountDownLatch,
            done: CountDownLatch,
        ) = Thread {
            try {
                slot.run(
                    start = {
                        check(owner.compareAndSet(0, identity)) { "Resource still occupied" }
                        ready.countDown()
                    },
                    stop = {
                        owner.compareAndSet(identity, 0)
                        Unit
                    },
                ) { it.await(60_000) }
            } catch (failure: Throwable) {
                error.set(failure)
            }
            done.countDown()
        }.apply {
            isDaemon = true
            start()
        }
        launch(1, started, returned)
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            launch(2, replacementStarted, replacementReturned)
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(null, error.get())
            assertEquals(2, owner.get())
            assertTrue(slot.isActive)
            slot.stop()
            assertTrue(replacementReturned.await(2, TimeUnit.SECONDS))
            assertEquals(0, owner.get())
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `failed start releases partial resource`() {
        val slot = AudioOperationSlot()
        val owner = AtomicInteger(0)
        try {
            slot.run(
                start = {
                    owner.set(1)
                    throw IllegalStateException("device refused")
                },
                stop = { owner.set(0) },
            ) { Unit }
            org.junit.Assert.fail("Expected native start failure")
        } catch (error: IllegalStateException) {
            assertEquals("device refused", error.message)
        }
        assertEquals(0, owner.get())
        assertFalse(slot.isActive)
    }
}
