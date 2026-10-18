// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Background sync is a chain of one-time workers (vauchi/private#603): a
 * run that gives up must still arm the next one, and must not end as a
 * failure, since WorkManager fails the work appended after a failed run.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerRearmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
    }

    @Test
    fun aRunThatCannotOpenStorageStillArmsTheNextOne() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        worker.repositoryFactory = { throw IllegalStateException("no storage") }

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        val queued =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME)
                .get()
                .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, queued.size)
    }
}
