// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import app.vauchi.data.VauchiRepository
import app.vauchi.ui.coreui.WakeupOutcome
import app.vauchi.ui.coreui.toPresentation
import app.vauchi.util.NetworkMonitor
import kotlinx.serialization.json.Json
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.hardwareEventJson

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val wakeupJson = Json { ignoreUnknownKeys = true }

    companion object {
        const val TAG = "SyncWorker"
    }

    /**
     * Seam for tests: the process-wide singleton builds a real
     * [app.vauchi.data.KeyStoreHelper], so a test cannot otherwise choose
     * how storage init behaves.
     */
    internal var repositoryFactory: (Context) -> VauchiRepository = VauchiRepository::getInstance

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync")

        val repository =
            try {
                repositoryFactory(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Repository init failed: ${e.message}", e)
                return giveUp()
            }

        val maxRetries = 3u

        return try {
            // Skip all engine work until the user has completed onboarding.
            // Without an identity, periodicSyncTick() returns NoIdentity anyway,
            // but touching the shared engine during the onboarding flow races
            // against the foreground renderer and can transiently corrupt the
            // locale catalog / screen state that CoreAppViewModel is displaying.
            //
            // Inside the guard, not above it: storage initialises lazily behind
            // this call, so on a locked device it throws instead of answering.
            if (!repository.hasIdentity()) {
                Log.d(TAG, "No identity yet; skipping background sync")
                rearm(emptyList())
                return Result.success()
            }

            // The tick names this worker's next run (#603): a background gap,
            // also while the app is open, where the heartbeat's wakeup beats
            // every 30 s and is not ours to follow.
            // Core draws the next run within what Android allows us (#603).
            runCatching {
                repository.appEngine.dispatchJson(
                    eventJson = StandbyBudget.current(applicationContext).eventJson(),
                )
            }.onFailure { Log.w(TAG, "background budget not reported: ${it.javaClass.simpleName}") }
            val nextRun =
                runCatching {
                    wakeupJson.decodeFromString<WakeupOutcome>(repository.appEngine.periodicSyncTick()).commands
                }.getOrElse { emptyList() }

            // A worker process has not heard the network cost from the
            // app, and Core counts an unknown network as metered.
            runCatching {
                repository.appEngine.dispatchJson(
                    eventJson = hardwareEventJson(
                        MobileEvent.NetworkCostChanged(NetworkMonitor(applicationContext).currentCost()),
                    ),
                )
            }.onFailure { Log.w(TAG, "network cost not reported: ${it.javaClass.simpleName}") }
            runCatching { repository.runContentUpdateCycle() }
                .onFailure { Log.w(TAG, "[ContentUpdate] skipped: ${it.javaClass.simpleName}") }

            val outcome =
                runCatching {
                    wakeupJson.decodeFromString<WakeupOutcome>(repository.appEngine.onWakeup())
                }.getOrElse {
                    Log.e(TAG, "on_wakeup failed: ${it.message}", it)
                    WakeupOutcome()
                }
            rearm(nextRun)
            val notifications = outcome.notifications.map { it.toPresentation() }
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
                giveUp()
            }
        }
    }

    /**
     * End this run without failing it: WorkManager fails the work appended
     * after a failed run, which would end the chain (#603).
     */
    private suspend fun giveUp(): Result {
        rearm(emptyList())
        return Result.success()
    }

    /**
     * Arm the next run so the chain never ends; a retry needs none, since
     * WorkManager comes back to this same work by itself.
     */
    private suspend fun rearm(commands: List<app.vauchi.ui.coreui.CommandDTO>) {
        val delay =
            BackgroundSyncScheduler.nextDelayMillis(
                commands,
                BackgroundSyncScheduler.lastDelayMillis(applicationContext),
            )
        // Stored before this run reports its result, so the chain cannot be
        // lost to a process that dies right after.
        BackgroundSyncScheduler.arm(applicationContext, delay).await()
    }
}
