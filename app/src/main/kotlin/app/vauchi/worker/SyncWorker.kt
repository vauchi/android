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

        // This worker builds its OWN VauchiRepository/engine per tick — a
        // separate instance from the foreground CoreAppViewModel's engine, which
        // holds the only ScreenInvalidationListener (core's engine_cache is
        // per-engine). A device-sync update applied here therefore fires
        // onScreensInvalidated on a listener-less engine and does NOT live-refresh
        // a screen the user is parked on.
        //
        // Decision — 2026-06-30-sync-ui-invalidation-sibling-gaps Gap B: accept
        // the next-resume refresh as the contract. The foreground engine re-syncs
        // on ON_RESUME (MainActivity -> viewModel.sync()) and, now that core's
        // apply_sync_items dispatches a VauchiEvent per applied arm (Gap A), that
        // resync live-refreshes the affected screens. Storage is always written
        // correctly; the only uncovered window is "foregrounded and parked through
        // a 15-min worker tick with no intervening resume" — narrow and
        // recoverable by navigation. Closing it live would require sharing one
        // engine (and its listener) across the worker and the foreground VM, or a
        // cross-engine invalidation channel — deferred as disproportionate to the
        // window.
        val repository =
            try {
                VauchiRepository(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Repository init failed: ${e.message}", e)
                return Result.failure()
            }
        val maxRetries = 3u

        return try {
            // Per-tick decision (gate on identity / OHTTP key, honour
            // throttle window) lives in core (audit
            // `2026-04-28-lifecycle-session-residue-umbrella` P2-C).
            // The worker shrinks to a single core call plus
            // notification polling.
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
