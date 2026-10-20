// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.vauchi.ui.coreui.CommandDTO
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * Background sync re-arms a one-time worker at the gap Core draws
 * (vauchi/private#603 option 2). The fixed 15-min periodic job it replaces
 * gave every background sync the same spacing, a fingerprint a relay hop
 * could recognise (KU-9 rerun, 2026-10-16).
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundSyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
    }

    private fun queued(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun theNextDelayIsTheOneCoreScheduled() {
        val commands = listOf(CommandDTO.ScheduleWakeup(612u, 612u, null, 612_000u))

        assertEquals(612_000L, BackgroundSyncScheduler.nextDelayMillis(commands, lastMillis = 900_000L))
    }

    @Test
    fun withoutAScheduleWakeupTheLastDelayIsReused() {
        assertEquals(437_000L, BackgroundSyncScheduler.nextDelayMillis(emptyList(), lastMillis = 437_000L))
    }

    @Test
    fun armingQueuesOneSyncAfterTheDelay() {
        BackgroundSyncScheduler.arm(context, 612_000L).result.get()

        val work = queued(BackgroundSyncScheduler.WORK_NAME)
        assertEquals(1, work.size)
        assertEquals(612_000L, work.single().initialDelayMillis)
    }

    @Test
    fun startingCancelsTheFixedPeriodicSyncAndArmsTheChain() {
        workManager
            .enqueueUniquePeriodicWork(
                BackgroundSyncScheduler.LEGACY_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IdleWorker>(15, TimeUnit.MINUTES).build(),
            ).result
            .get()

        BackgroundSyncScheduler.start(context).result.get()

        assertEquals(emptyList<WorkInfo>(), queued(BackgroundSyncScheduler.LEGACY_PERIODIC_WORK_NAME))
        assertEquals(1, queued(BackgroundSyncScheduler.WORK_NAME).size)
    }

    @Test
    fun startingAgainKeepsTheArmedSync() {
        BackgroundSyncScheduler.arm(context, 612_000L).result.get()

        BackgroundSyncScheduler.start(context).result.get()

        assertEquals(612_000L, queued(BackgroundSyncScheduler.WORK_NAME).single().initialDelayMillis)
    }

    // A gap drawn in a quiet `rare` spell held for 24 h after the app was
    // opened: no sync in 3 h at working_set (#603 run A2).
    @Test
    fun closingTheAppReplacesTheArmedSyncWithCoresAnswer() {
        BackgroundSyncScheduler.arm(context, 86_400_000L).result.get()

        BackgroundSyncScheduler.rearmOnClose(context) { wakeupEnvelope(612_000) }!!.result.get()

        assertEquals(612_000L, queued(BackgroundSyncScheduler.WORK_NAME).single().initialDelayMillis)
    }

    @Test
    fun closingTheAppReportsTheStandbyBucketsAllowance() {
        var reported: String? = null

        BackgroundSyncScheduler.rearmOnClose(context) {
            reported = it
            wakeupEnvelope(612_000)
        }

        assertEquals(StandbyBudget.current(context).eventJson(), reported)
    }

    @Test
    fun withoutAWakeupClosingTheAppKeepsTheArmedSync() {
        BackgroundSyncScheduler.arm(context, 612_000L).result.get()

        val operation = BackgroundSyncScheduler.rearmOnClose(context) { """{"commands":[]}""" }

        assertEquals(null, operation)
        assertEquals(612_000L, queued(BackgroundSyncScheduler.WORK_NAME).single().initialDelayMillis)
    }

    private fun wakeupEnvelope(millis: Int): String {
        val secs = (millis + 999) / 1000
        return """{"commands":[{"ScheduleWakeup":{"earliest_secs":$secs,"deadline_secs":$secs,""" +
            """"earliest_millis":$millis,"delay_millis":$millis}}]}"""
    }
}

/**
 * Stands in for the legacy periodic job: the test WorkManager runs a periodic
 * request at once, and a real [SyncWorker] there would arm work of its own
 * that leaks into the next test.
 */
class IdleWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
