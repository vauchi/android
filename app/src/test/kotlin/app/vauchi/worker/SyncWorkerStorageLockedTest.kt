// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.app.KeyguardManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.vauchi.data.AuthenticationRequiredException
import app.vauchi.data.OldStorageKeyProvider
import app.vauchi.data.VauchiRepository
import app.vauchi.data.installFromBefore
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Periodic sync runs on WorkManager's schedule, which routinely wakes a
 * *cold* process while the device is locked. On release builds the storage
 * key needs user authentication a background worker can never obtain, so
 * Core starts locked (vauchi/private#580) and cannot say whether there is an
 * identity yet.
 *
 * A locked device is an expected condition, not a fault: the work simply
 * has not been done yet. It must be reported as `retry` so WorkManager
 * comes back, rather than as "nothing to do" or an outright failure.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerStorageLockedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        assumeTrue("host vauchi-platform library missing", HostCoreLibrary.present())
        FakeAndroidKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        // VauchiRepository's init rejects a device with no lock screen;
        // Robolectric reports none by default.
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
    }

    @Test
    fun aLockedStorageKeyDefersTheSyncInsteadOfEscaping() {
        val provider = OldStorageKeyProvider()
        installFromBefore(context, provider)
        provider.failure = AuthenticationRequiredException("Device must be unlocked to access encryption keys")
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        worker.repositoryFactory = { VauchiRepository(it, provider) }

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
