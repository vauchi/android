// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.app.KeyguardManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Verifies the F2-CRIT-1 recovery seam in [VauchiRepository]:
 *
 * - When [StorageKeyProvider] throws [KeyInvalidatedException] (the wrapped
 *   form of `KeyPermanentlyInvalidatedException` / `AEADBadTagException`),
 *   the repository must wipe local encrypted state (DB files +
 *   encrypted-storage-key blob in prefs + master-key alias) and surface
 *   [KeyInvalidatedRecoveryRequired] to the caller.
 * - The `hadData` flag on the recovery exception reflects whether the user
 *   previously had a working identity (i.e. the encrypted-storage-key blob
 *   existed before the wipe). Used by [MainViewModel] to either show the
 *   recovery screen (`hadData=true`) or route silently to onboarding
 *   (`hadData=false`, true fresh install with an inherited bad alias).
 *
 * The test injects a fake [StorageKeyProvider] that throws on the call
 * paths exercised by `getOrCreateStorageKey`. We do not exercise UniFFI,
 * so this test deliberately stops at the storage-init boundary by
 * triggering recovery before any `platform()` call needs the bindings.
 */
@RunWith(RobolectricTestRunner::class)
class KeyInvalidatedRecoveryTest {
    private lateinit var context: Context
    private lateinit var dataDir: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // VauchiRepository's init requires a secure lock screen — Robolectric
        // returns false by default; flip it so construction succeeds.
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(km).setIsDeviceSecure(true)

        dataDir = context.filesDir.absolutePath
        // Seed a stale encrypted DB file so we can assert the wipe deletes it.
        File(dataDir, "vauchi.db").writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        File(dataDir, "vauchi.db-wal").writeBytes(byteArrayOf(0x04))
        File(dataDir, "vauchi.db-shm").writeBytes(byteArrayOf(0x05))

        // Clear any stale prefs from prior runs.
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        File(dataDir, "vauchi.db").delete()
        File(dataDir, "vauchi.db-wal").delete()
        File(dataDir, "vauchi.db-shm").delete()
        File(dataDir, "vauchi.db-journal").delete()
    }

    /**
     * Fake provider that simulates a permanently-invalidated KeyStore
     * alias: every cipher operation surfaces a [KeyInvalidatedException]
     * just like the real [KeyStoreHelper] does after our F2-CRIT-1
     * translation of `KeyPermanentlyInvalidatedException`.
     */
    private class AlwaysInvalidatedProvider : StorageKeyProvider {
        var deleteMasterKeyCalls = 0

        override fun generateEncryptedStorageKey(): ByteArray =
            throw KeyInvalidatedException("simulated KPIE on encrypt", IllegalStateException("test"))

        override fun encryptStorageKey(storageKey: ByteArray): ByteArray =
            throw KeyInvalidatedException("simulated KPIE on encrypt", IllegalStateException("test"))

        override fun decryptStorageKey(encryptedData: ByteArray): ByteArray =
            throw KeyInvalidatedException("simulated KPIE on decrypt", IllegalStateException("test"))

        override fun hasMasterKey(): Boolean = true

        override fun deleteMasterKey() {
            deleteMasterKeyCalls += 1
        }
    }

    @Test
    fun freshInstall_kpieOnGenerate_signalsRecoveryWithHadDataFalse() {
        val provider = AlwaysInvalidatedProvider()
        val repository = VauchiRepository(context, provider)

        // No encrypted-storage-key blob in prefs → fresh-install path.
        // hasIdentity() drives platform() → getOrCreateStorageKey() → KPIE
        // → wipe → KeyInvalidatedRecoveryRequired(hadData=false).
        val ex =
            assertThrows(KeyInvalidatedRecoveryRequired::class.java) {
                repository.hasIdentity()
            }
        assertFalse("hadData must be false on a true fresh install", ex.hadData)
        assertNotNull("cause should chain back to the underlying KPIE", ex.cause)
        assertTrue(
            "cause message should originate from KeyStoreHelper translation",
            ex.cause?.message?.contains("simulated KPIE") == true,
        )
    }

    @Test
    fun previouslyOnboarded_kpieOnDecrypt_signalsRecoveryWithHadDataTrueAndWipes() {
        // Pre-seed prefs with a (placeholder) encrypted-storage-key blob to
        // simulate a previously-onboarded user whose alias has now been
        // invalidated.
        val prefs =
            context.getSharedPreferences(
                VauchiPreferences.PREFS_NAME,
                Context.MODE_PRIVATE,
            )
        prefs
            .edit()
            .putString("encrypted_storage_key", "AAAAAAAAAAAAAAAAAAAAAAAA")
            .commit()

        val provider = AlwaysInvalidatedProvider()
        val repository = VauchiRepository(context, provider)

        val ex =
            assertThrows(KeyInvalidatedRecoveryRequired::class.java) {
                repository.hasIdentity()
            }

        assertTrue("hadData must be true when prefs had a key blob before init", ex.hadData)
        assertEquals(
            "deleteMasterKey() must run exactly once during the wipe",
            1,
            provider.deleteMasterKeyCalls,
        )
        assertNull(
            "encrypted-storage-key blob must be cleared from prefs",
            prefs.getString("encrypted_storage_key", null),
        )
        assertFalse(
            "vauchi.db must be deleted by the wipe",
            File(dataDir, "vauchi.db").exists(),
        )
        assertFalse(
            "vauchi.db-wal must be deleted by the wipe",
            File(dataDir, "vauchi.db-wal").exists(),
        )
        assertFalse(
            "vauchi.db-shm must be deleted by the wipe",
            File(dataDir, "vauchi.db-shm").exists(),
        )
    }
}
