// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.vauchi.data.VauchiRepository

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "vauchi_periodic_sync"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync")

        // Build the repository once, before the per-tick try block,
        // so the catch arm can read core's max-retries constant.
        val repository =
            try {
                VauchiRepository(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Repository init failed: ${e.message}", e)
                return Result.failure()
            }
        val maxRetries =
            runCatching { repository.appEngine.periodicSyncMaxRetries() }
                .getOrDefault(3u)

        return try {
            // Per-tick decision (gate on identity / OHTTP key, honour
            // throttle window) lives in core (audit
            // `2026-04-28-lifecycle-session-residue-umbrella` P2-C).
            // The worker shrinks to a single core call plus
            // notification polling. The retry budget mirrors
            // core's PERIODIC_SYNC_MAX_RETRIES.
            repository.appEngine.periodicSyncTick()

            // Poll for pending notifications (E)
            val notifications = repository.pollNotifications()
            for (notification in notifications) {
                app.vauchi.util.NotificationHelper
                    .showNotification(applicationContext, notification)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            if (runAttemptCount.toUInt() < maxRetries) {
                Log.d(TAG, "Retrying sync (attempt ${runAttemptCount + 1} of $maxRetries)")
                Result.retry()
            } else {
                Log.e(TAG, "Max retry attempts ($maxRetries) reached, giving up")
                Result.failure()
            }
        }
    }
}
