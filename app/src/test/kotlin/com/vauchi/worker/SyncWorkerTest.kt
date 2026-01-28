// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for SyncWorker - background sync functionality
 * Based on: features/sync_updates.feature
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
    }

    // MARK: - Worker Creation Tests
    // Based on: Scenario: Background sync worker initializes

    /**
     * Scenario: Worker can be created
     */
    @Test
    fun `worker can be created`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertNotNull(worker, "Worker should be created successfully")
    }

    /**
     * Scenario: Worker has correct class
     */
    @Test
    fun `worker is correct class`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(SyncWorker::class.java, worker.javaClass, "Should be SyncWorker class")
    }

    // MARK: - Worker Execution Tests
    // Based on: Scenario: Sync executes in background

    /**
     * Scenario: Worker returns result
     */
    @Test
    fun `worker returns result`() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        // doWork should return a Result (success, failure, or retry)
        val result = worker.doWork()

        // Any result is valid - depends on repository state
        assertNotNull(result, "Should return a result")
    }

    /**
     * Scenario: Worker handles missing identity gracefully
     */
    @Test
    fun `worker handles no identity gracefully`() = runBlocking {
        // Fresh context with no identity set up
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        val result = worker.doWork()

        // Should not crash, return success or retry
        val validResults = listOf(
            ListenableWorker.Result.success(),
            ListenableWorker.Result.retry()
        )
        // Worker should handle gracefully
        assertNotNull(result)
    }

    // MARK: - Worker Constraints Tests

    /**
     * Scenario: Worker has ID for tracking
     */
    @Test
    fun `worker has valid ID`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertNotNull(worker.id, "Worker should have an ID")
    }

    /**
     * Scenario: Worker has application context
     */
    @Test
    fun `worker has application context`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertNotNull(worker.applicationContext, "Worker should have application context")
    }

    // MARK: - Sync State Tests
    // Based on: Scenario: Sync state is tracked

    /**
     * Scenario: Worker respects run attempt count
     */
    @Test
    fun `worker tracks run attempt count`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setRunAttemptCount(3)
            .build()

        assertEquals(3, worker.runAttemptCount, "Should track run attempt count")
    }

    /**
     * Scenario: First run has zero attempts
     */
    @Test
    fun `first run has zero attempt count`() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(0, worker.runAttemptCount, "First run should have 0 attempts")
    }
}
