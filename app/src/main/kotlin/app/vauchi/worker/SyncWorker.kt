// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.vauchi.data.VauchiRepository
import app.vauchi.ui.coreui.MobilePendingNotificationDTO
import app.vauchi.ui.coreui.WakeupOutcome
import app.vauchi.ui.coreui.toMobile
import kotlinx.serialization.json.Json

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

            val notifications =
                runCatching {
                    val outcomeJson = repository.appEngine.onWakeup()
                    val outcome = Json.decodeFromString<WakeupOutcome>(outcomeJson)
                    // Background ticks may emit ScheduleWakeup hints; the periodic
                    // WorkManager task already arms the next wakeup, so ignore them.
                    if (outcome.commands.isNotEmpty()) {
                        Log.d(TAG, "on_wakeup produced ${outcome.commands.size} command(s); ignoring in background")
                    }
                    outcome.notifications.map { it.toMobile() }
                }.getOrElse {
                    Log.e(TAG, "on_wakeup failed: ${it.message}", it)
                    emptyList()
                }
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
