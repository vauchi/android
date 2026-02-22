// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vauchi.data.VauchiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vauchi_mobile.MobileApplyResult
import uniffi.vauchi_mobile.MobileUpdateStatus
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
    companion object {
        const val TAG = "ContentUpdateWorker"
        const val WORK_NAME = "vauchi_content_update"

        /**
         * Schedule periodic content update checks.
         */
        fun schedule(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
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
            Log.d(TAG, "Starting content update check")

            try {
                val repository = VauchiRepository(applicationContext)

                // Check if content updates are supported
                if (!repository.isContentUpdatesSupported()) {
                    Log.d(TAG, "Content updates not supported, skipping")
                    return@withContext Result.success()
                }

                // Check for available updates via core
                val status = repository.checkContentUpdates()

                when (status) {
                    is MobileUpdateStatus.UpToDate -> {
                        Log.d(TAG, "Content is up to date")
                        return@withContext Result.success()
                    }

                    is MobileUpdateStatus.Disabled -> {
                        Log.d(TAG, "Content updates disabled")
                        return@withContext Result.success()
                    }

                    is MobileUpdateStatus.CheckFailed -> {
                        Log.e(TAG, "Content update check failed: ${status.error}")
                        return@withContext if (runAttemptCount < 3) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    }

                    is MobileUpdateStatus.UpdatesAvailable -> {
                        Log.d(TAG, "Updates available: ${status.types}")
                    }
                }

                // Apply available updates via core
                val applyResult = repository.applyContentUpdates()

                when (applyResult) {
                    is MobileApplyResult.Applied -> {
                        Log.d(
                            TAG,
                            "Content updates applied: ${applyResult.applied.size} applied, " +
                                "${applyResult.failed.size} failed",
                        )
                        // Reload social networks if networks were updated
                        if (applyResult.applied.any {
                                it == uniffi.vauchi_mobile.MobileContentType.NETWORKS
                            }
                        ) {
                            repository.reloadSocialNetworks()
                        }
                    }

                    is MobileApplyResult.NoUpdates -> {
                        Log.d(TAG, "No updates to apply")
                    }

                    is MobileApplyResult.Disabled -> {
                        Log.d(TAG, "Content updates disabled")
                    }

                    is MobileApplyResult.Error -> {
                        Log.e(TAG, "Failed to apply content updates: ${applyResult.error}")
                        return@withContext if (runAttemptCount < 3) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    }
                }

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Content update check failed: ${e.message}", e)
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
}
