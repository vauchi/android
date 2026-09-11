// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.Application
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * Application class Robolectric instantiates for every unit test
 * (`src/test/resources/robolectric.properties`). Unit tests include the
 * manifest so activities resolve their themes and assets, which would
 * otherwise instantiate [VauchiApp]; its `onCreate` schedules the periodic
 * sync through `WorkManager.getInstance`, and WorkManager's startup
 * provider does not run before `onCreate` under Robolectric.
 */
class RobolectricTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
    }
}
