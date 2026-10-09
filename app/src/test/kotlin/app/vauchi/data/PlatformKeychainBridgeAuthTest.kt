// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.vauchi.screenshots.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.KeyGenerator

/**
 * The key that wraps Core's keys (the SMK and the bootstrap key) needs the
 * person's authentication like the storage key always did
 * (vauchi/private#287 A1, #580). Deleting it never needs authentication, so
 * a shred works on a locked phone.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformKeychainBridgeAuthTest {
    private lateinit var context: Context

    private object NoOldKey : LegacyStorageKey {
        override fun load(): ByteArray? = null

        override fun delete() = Unit
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("keychain").deleteRecursively()
    }

    private fun bridge(requireUserAuth: Boolean) =
        PlatformKeychainBridge(context, NoOldKey, requireUserAuth = requireUserAuth)

    @Test
    fun theKeychainKeyNeedsAuthenticationWhenThePolicyRequiresIt() {
        bridge(requireUserAuth = true).saveKey("smk", ByteArray(32) { 1 })

        val spec = FakeAndroidKeyStore.specFor("vauchi_keychain_key_v2")
        assertTrue("spec $spec", spec?.isUserAuthenticationRequired == true)
    }

    @Test
    fun aDebugPolicyLeavesTheKeychainKeyUnbound() {
        bridge(requireUserAuth = false).saveKey("smk", ByteArray(32) { 1 })

        assertEquals(false, FakeAndroidKeyStore.specFor("vauchi_keychain_key_v2")?.isUserAuthenticationRequired)
    }

    @Test
    fun theUnboundKeychainKeyFromBeforeIsRemoved() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec
                    .Builder("vauchi_keychain_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

        bridge(requireUserAuth = true).saveKey("smk", ByteArray(32) { 1 })

        assertFalse(FakeAndroidKeyStore.hasAlias("vauchi_keychain_key"))
        assertTrue(FakeAndroidKeyStore.hasAlias("vauchi_keychain_key_v2"))
    }
}
