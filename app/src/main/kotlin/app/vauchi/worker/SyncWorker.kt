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

        val repository =
            try {
                VauchiRepository.getInstance(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Repository init failed: ${e.message}", e)
                return Result.failure()
            }

        // Skip all engine work until the user has completed onboarding.
        // Without an identity, periodicSyncTick() returns NoIdentity anyway,
        // but touching the shared engine during the onboarding flow races
        // against the foreground renderer and can transiently corrupt the
        // locale catalog / screen state that CoreAppViewModel is displaying.
        if (!repository.hasIdentity()) {
            Log.d(TAG, "No identity yet; skipping background sync")
            return Result.success()
        }

        val maxRetries = 3u

        return try {
            repository.appEngine.periodicSyncTick()

            runCatching { repository.runContentUpdateCycle() }
                .onFailure { Log.w(TAG, "[ContentUpdate] skipped: ${it.javaClass.simpleName}") }

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
