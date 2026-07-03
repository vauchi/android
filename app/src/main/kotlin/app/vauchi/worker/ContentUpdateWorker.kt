// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vauchi.data.VauchiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker for checking and applying content updates.
 *
 * Runs periodically to check for updates to networks, locales, themes, and help content.
 * Delegates all business logic (manifest diffing, download, verification) to vauchi-core
 * via UniFFI bindings through [VauchiRepository].
 */
class ContentUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** WorkManager result the worker should return for a cycle. */
    internal enum class CycleAction { SUCCESS, RETRY, FAILURE }

    companion object {
        const val TAG = "ContentUpdateWorker"
        const val WORK_NAME = "vauchi_content_update"

        /** Attempts (inclusive of the first run) before a retryable failure gives up. */
        const val MAX_ATTEMPTS = 3

        /**
         * Pure retry policy: a retryable failure retries while there is
         * attempt budget left, otherwise gives up; a clean cycle
         * succeeds. Kept engine-free so it is unit-testable directly
         * (the domain check→apply sequencing lives in core's
         * `RunContentUpdateCycle`).
         */
        internal fun cycleAction(
            retryableFailure: Boolean,
            attempt: Int,
        ): CycleAction =
            when {
                !retryableFailure -> CycleAction.SUCCESS
                attempt < MAX_ATTEMPTS -> CycleAction.RETRY
                else -> CycleAction.FAILURE
            }

        /**
         * Schedule periodic content update checks.
         */
        fun schedule(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<ContentUpdateWorker>(
                    1,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )

            Log.d(TAG, "Scheduled periodic content update checks")
        }

        /**
         * Cancel scheduled content update checks.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled content update checks")
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting content update cycle")

            // Core owns the whole check → apply → screen-invalidation
            // cycle (RunContentUpdateCycle); the worker only maps the
            // outcome to a WorkManager result. A thrown dispatch failure
            // (e.g. native lib unavailable) is treated as retryable.
            val action =
                try {
                    val outcome = VauchiRepository(applicationContext).runContentUpdateCycle()
                    Log.d(
                        TAG,
                        "Content update cycle: applied=${outcome.applied}, " +
                            "retryable=${outcome.retryableFailure}",
                    )
                    cycleAction(outcome.retryableFailure, runAttemptCount)
                } catch (e: Exception) {
                    Log.e(TAG, "[ContentUpdate] Failed: ${e.javaClass.simpleName}", e)
                    cycleAction(retryableFailure = true, attempt = runAttemptCount)
                }

            when (action) {
                CycleAction.SUCCESS -> Result.success()
                CycleAction.RETRY -> Result.retry()
                CycleAction.FAILURE -> Result.failure()
            }
        }
}
