// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import app.vauchi.screenshots.FakeAndroidKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.vauchi_platform.KeychainException

/**
 * Core opens storage through the keychain (vauchi/private#580). The storage
 * key this app kept before is served as Core's bootstrap key until Core
 * moves the data to its own keys, and a locked or invalidated Keystore
 * reaches Core as its own kind, so Core shows the unlock or recovery screen.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformKeychainBridgeHandoverTest {
    private lateinit var context: Context

    /** The storage key the app kept itself before #580. */
    private class OldStorageKey(
        var key: ByteArray?,
        var failure: Exception? = null,
    ) : LegacyStorageKey {
        override fun load(): ByteArray? {
            failure?.let { throw it }
            return key
        }

        override fun delete() {
            key = null
        }
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("keychain").deleteRecursively()
    }

    private fun bridge(old: LegacyStorageKey) = PlatformKeychainBridge(context, old)

    @Test
    fun theOldStorageKeyIsTheBootstrapKeyUntilCoreStoresItsOwn() {
        val oldKey = ByteArray(32) { 7 }
        val bridge = bridge(OldStorageKey(oldKey))

        assertArrayEquals(oldKey, bridge.loadKey("storage_bootstrap"))

        val coreKey = ByteArray(32) { 9 }
        bridge.saveKey("storage_bootstrap", coreKey)
        assertArrayEquals(coreKey, bridge.loadKey("storage_bootstrap"))
    }

    @Test
    fun onlyTheBootstrapNameFallsBackToTheOldKey() {
        val bridge = bridge(OldStorageKey(ByteArray(32) { 7 }))

        assertNull(bridge.loadKey("smk"))
    }

    @Test
    fun deletingTheBootstrapKeyDeletesTheOldKeyToo() {
        val old = OldStorageKey(ByteArray(32) { 7 })
        val bridge = bridge(old)
        bridge.saveKey("storage_bootstrap", ByteArray(32) { 9 })

        bridge.deleteKey("storage_bootstrap")

        assertNull(old.key)
        assertNull(bridge.loadKey("storage_bootstrap"))
    }

    @Test
    fun anOldKeyThatNeedsAnUnlockReachesCoreAsAuthenticationRequired() {
        val bridge = bridge(OldStorageKey(null, AuthenticationRequiredException("unlock")))

        assertThrows(KeychainException.AuthenticationRequired::class.java) {
            bridge.loadKey("storage_bootstrap")
        }
    }

    @Test
    fun anInvalidatedOldKeyReachesCoreAsKeyInvalidated() {
        val bridge = bridge(OldStorageKey(null, KeyInvalidatedException("gone", null)))

        assertThrows(KeychainException.KeyInvalidated::class.java) {
            bridge.loadKey("storage_bootstrap")
        }
    }

    @Test
    fun aKeystoreThatNeedsAnUnlockReachesCoreAsAuthenticationRequired() {
        val bridge =
            PlatformKeychainBridge(context, OldStorageKey(null)) {
                throw UserNotAuthenticatedException()
            }

        val failure = runCatching { bridge.saveKey("smk", ByteArray(32) { 1 }) }.exceptionOrNull()

        assertTrue("got $failure", failure is KeychainException.AuthenticationRequired)
    }
}
