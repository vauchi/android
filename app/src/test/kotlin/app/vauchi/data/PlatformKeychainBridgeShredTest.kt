// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import app.vauchi.screenshots.FakeAndroidKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * A shred deletes every key Core keeps here (vauchi/private#599). Deleting
 * the key files is not enough: a copy of `files/` taken before the shred
 * holds them, and the Keystore key that wraps them would still open that
 * copy. Found on the rig Pixel: a restored pre-shred copy opened again.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformKeychainBridgeShredTest {
    private lateinit var context: Context
    private lateinit var bridge: PlatformKeychainBridge

    private class NoOldKey : LegacyStorageKey {
        override fun load(): ByteArray? = null

        override fun delete() = Unit
    }

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("keychain").deleteRecursively()
        bridge = PlatformKeychainBridge(context, NoOldKey(), requireUserAuth = false)
    }

    private fun keyFile(name: String) = File(context.filesDir, "keychain/$name")

    @Test
    fun aKeyFileRestoredAfterTheShredNoLongerOpens() {
        val smk = ByteArray(32) { 3 }
        bridge.saveKey("smk", smk)
        bridge.saveKey("storage_bootstrap", ByteArray(32) { 4 })
        val copy = keyFile("smk").readBytes()

        bridge.deleteKey("smk")
        bridge.deleteKey("storage_bootstrap")
        assertFalse("the Keystore key that wraps the key files survived", FakeAndroidKeyStore.hasAlias(KEYSTORE_ALIAS))
        keyFile("smk").writeBytes(copy)

        val reopened = runCatching { bridge.loadKey("smk") }.getOrNull()
        assertTrue("a restored pre-shred key opened again", reopened == null || !reopened.contentEquals(smk))
    }

    @Test
    fun deletingOneKeyKeepsTheOthersReadable() {
        val smk = ByteArray(32) { 3 }
        bridge.saveKey("smk", smk)
        bridge.saveKey("storage_bootstrap", ByteArray(32) { 4 })

        bridge.deleteKey("storage_bootstrap")

        assertArrayEquals(smk, bridge.loadKey("smk"))
        assertTrue(FakeAndroidKeyStore.hasAlias(KEYSTORE_ALIAS))
    }

    private companion object {
        const val KEYSTORE_ALIAS = "vauchi_keychain_key_v2"
    }
}
