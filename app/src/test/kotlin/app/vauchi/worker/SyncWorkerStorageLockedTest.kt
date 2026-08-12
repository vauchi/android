// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.app.KeyguardManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.vauchi.data.AuthenticationRequiredException
import app.vauchi.data.StorageKeyProvider
import app.vauchi.data.VauchiRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Periodic sync runs on WorkManager's schedule, which routinely wakes a
 * *cold* process while the device is locked. Storage initialises lazily
 * behind the first repository call, and on release builds
 * (`setUserAuthenticationRequired(!DEBUG)`) that key needs user
 * authentication a background worker can never obtain.
 *
 * A locked device is an expected condition, not a fault: the work simply
 * has not been done yet. It must be reported as `retry` so WorkManager
 * comes back, rather than escaping `doWork` — which WorkManager records as
 * an outright failure, skipping the retry the worker already implements.
 *
 * Same defect class as the launch content-update cycle fixed in
 * `vauchi/android!625`: a guard placed below the call that touches storage
 * rather than around it.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerStorageLockedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // VauchiRepository's init rejects a device with no lock screen;
        // Robolectric reports none by default.
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
    }

    @Test
    fun aLockedStorageKeyDefersTheSyncInsteadOfEscaping() {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        worker.repositoryFactory = { VauchiRepository(it, LockedStorageKeyProvider()) }

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    /**
     * Stands in for a release-build KeyStore on a locked device: the key
     * exists, but every operation that would use it demands authentication.
     */
    private class LockedStorageKeyProvider : StorageKeyProvider {
        override fun generateEncryptedStorageKey(): ByteArray = throw locked()

        override fun encryptStorageKey(storageKey: ByteArray): ByteArray = throw locked()

        override fun decryptStorageKey(encryptedData: ByteArray): ByteArray = throw locked()

        override fun hasMasterKey(): Boolean = true

        override fun deleteMasterKey() = Unit

        private fun locked() = AuthenticationRequiredException("Device must be unlocked to access encryption keys")
    }
}
