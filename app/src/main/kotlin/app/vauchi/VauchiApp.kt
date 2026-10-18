// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.Application
import android.util.Log
import app.vauchi.worker.BackgroundSyncScheduler
import uniffi.vauchi_platform.initMobileLogging

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
        BackgroundSyncScheduler.start(this)
    }

    companion object {
        private const val TAG = "VauchiApp"

        lateinit var instance: VauchiApp
            private set
    }
}
