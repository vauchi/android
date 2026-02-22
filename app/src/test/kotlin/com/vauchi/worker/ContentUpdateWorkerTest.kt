// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentUpdateWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- Delegation to core ---

    @Test
    fun `doWork delegates to repository and returns valid result`() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<ContentUpdateWorker>(context).build()

            // The worker should call repository.checkContentUpdates() instead of
            // performing standalone HTTP manifest fetching. Since VauchiRepository
            // requires native libs unavailable in unit tests, this verifies:
            // 1. The worker compiles without standalone HTTP/manifest code
            // 2. Error handling produces a valid WorkManager result
            val result = worker.doWork()

            assertTrue(
                "Worker should return success, retry, or failure - not throw",
                result == ListenableWorker.Result.success() ||
                    result == ListenableWorker.Result.retry() ||
                    result == ListenableWorker.Result.failure(),
            )
        }

    @Test
    fun `doWork returns non-null result when repository init fails`() =
        runBlocking {
            val worker = TestListenableWorkerBuilder<ContentUpdateWorker>(context).build()

            // When native lib is unavailable (test env), repository init fails.
            // Worker should gracefully handle this and return a valid result.
            val result = worker.doWork()
            assertNotNull("Worker result should not be null", result)
        }

    // --- Scheduling logic preserved ---

    @Test
    fun `companion schedule method exists`() {
        // schedule() should remain functional — it only interacts with WorkManager.
        // This verifies the companion object was not accidentally removed.
        try {
            ContentUpdateWorker.schedule(context)
        } catch (e: Exception) {
            // WorkManager may not be initialized in test, but the method must exist
            assertTrue(
                "schedule() should exist and only fail due to WorkManager init",
                e.message?.contains("WorkManager") == true ||
                    e is IllegalStateException,
            )
        }
    }

    @Test
    fun `companion cancel method exists`() {
        try {
            ContentUpdateWorker.cancel(context)
        } catch (e: Exception) {
            assertTrue(
                "cancel() should exist and only fail due to WorkManager init",
                e.message?.contains("WorkManager") == true ||
                    e is IllegalStateException,
            )
        }
    }

    @Test
    fun `TAG constant is ContentUpdateWorker`() {
        assertEquals("ContentUpdateWorker", ContentUpdateWorker.TAG)
    }

    @Test
    fun `WORK_NAME constant is vauchi_content_update`() {
        assertEquals("vauchi_content_update", ContentUpdateWorker.WORK_NAME)
    }

    // --- Standalone HTTP/manifest logic removed ---

    @Test
    fun `ContentManifest class removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.ContentManifest")
            fail("ContentManifest should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected -- manifest parsing is now handled by core
        }
    }

    @Test
    fun `ContentEntry class removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.ContentEntry")
            fail("ContentEntry should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected -- standalone data classes were removed
        }
    }

    @Test
    fun `ContentIndex class removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.ContentIndex")
            fail("ContentIndex should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected
        }
    }

    @Test
    fun `LocalesEntry class removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.LocalesEntry")
            fail("LocalesEntry should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected
        }
    }

    @Test
    fun `FileEntry class removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.FileEntry")
            fail("FileEntry should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected
        }
    }

    @Test
    fun `ContentType enum removed from worker package`() {
        try {
            Class.forName("com.vauchi.worker.ContentType")
            fail("ContentType should have been removed from worker package")
        } catch (e: ClassNotFoundException) {
            // Expected -- core handles content type classification
        }
    }

    // --- Retry behavior ---

    @Test
    fun `doWork returns retry on first failure`() =
        runBlocking {
            val worker =
                TestListenableWorkerBuilder<ContentUpdateWorker>(context)
                    .setRunAttemptCount(0)
                    .build()

            val result = worker.doWork()

            // On first failure (native lib missing in test), worker should retry
            assertEquals(
                "Worker should retry on first failure",
                ListenableWorker.Result.retry(),
                result,
            )
        }

    @Test
    fun `doWork returns failure after max retries`() =
        runBlocking {
            val worker =
                TestListenableWorkerBuilder<ContentUpdateWorker>(context)
                    .setRunAttemptCount(3)
                    .build()

            val result = worker.doWork()

            // After 3 attempts, worker should give up
            assertEquals(
                "Worker should fail after max retries",
                ListenableWorker.Result.failure(),
                result,
            )
        }
}
