// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.vauchi.worker.SyncWorker
import uniffi.vauchi_platform.initMobileLogging
import java.util.concurrent.TimeUnit

class VauchiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else: WorkManager below can run a SyncWorker
        // this same process launch, and Rust-side log::warn!/error! calls
        // (BLE handshake, exchange, sync failure paths) are silent until
        // this installs the Logcat backend (2026-06-08-magic-audio-
        // proximity-driver deferred this permanent version).
        // Log the install status via Kotlin's Logcat path (which works even
        // on devices where the Rust tracing bridge fails to attach), so an
        // install failure is observable without the bridge it would need to
        // report itself (S7 dev-logging investigation 2026-07-25).
        val logStatus = initMobileLogging()
        Log.i("Vauchi", "[boot] mobile logging: $logStatus")
        instance = this
        schedulePeriodicSync()
    }

    private fun schedulePeriodicSync() {
        Log.d(TAG, "Scheduling periodic sync")

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            ).setConstraints(constraints)
                .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )

        Log.d(TAG, "Periodic sync scheduled (every 15 minutes)")
    }

    companion object {
        private const val TAG = "VauchiApp"

        lateinit var instance: VauchiApp
            private set
    }
}
