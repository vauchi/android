// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.widgetPanicShred
import java.io.File

/**
 * A shred destroys the key that opens the data, not only the files
 * (vauchi/private#580, ADR-033, ship checklist SC-24): a copy of `files/`
 * taken before the shred and put back afterwards no longer opens, through
 * the real engine and the real keychain bridge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class ShredDestroysTheStorageKeyTest {
    private lateinit var context: Context
    private lateinit var dataDir: File
    private lateinit var copyDir: File
    private lateinit var keyProvider: OldStorageKeyProvider
    private val engines = mutableListOf<PlatformAppEngine>()

    @Before
    fun setUp() {
        HostCoreLibrary.assumePresentInSharedSandbox()
        FakeAndroidKeyStore.install()
        keyProvider = OldStorageKeyProvider()
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        dataDir = context.filesDir
        dataDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        copyDir = File(context.cacheDir, "files-before-shred").apply { deleteRecursively() }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
    }

    @After
    fun tearDown() {
        engines.forEach(PlatformAppEngine::close)
        // setUp skips before copyDir is set when the host library is absent.
        if (::copyDir.isInitialized) copyDir.deleteRecursively()
    }

    private fun open(): VauchiRepository = VauchiRepository(context, keyProvider).also { engines.add(it.appEngine) }

    /** Alice's install, closed, and a copy of its `files/` as an imager would take it. */
    private fun aliceWithACopyOfHerFiles() {
        val repository = open()
        repository.createIdentity("Alice")
        assertTrue(repository.appEngine.hasIdentity())
        engines.remove(repository.appEngine)
        repository.appEngine.close()
        dataDir.copyRecursively(copyDir, overwrite = true)
    }

    private fun putTheCopyBack() {
        dataDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        copyDir.copyRecursively(dataDir, overwrite = true)
    }

    @Test
    fun shredDestroysTheStorageKey() {
        aliceWithACopyOfHerFiles()

        widgetPanicShred(
            dataDir.absolutePath,
            PlatformKeychainBridge(context, PreferencesLegacyStorageKey(context, keyProvider)),
        )
        putTheCopyBack()

        assertTrue(File(dataDir, "vauchi.db").exists())
        val engine = open().appEngine
        val surface = Regex("\"surface_id\":\"([^\"]+)\"").find(engine.initialCommandsJson())?.groupValues?.get(1)
        assertTrue("the restored copy opened on $surface", surface.orEmpty().startsWith("storage_lock."))
        assertThrows(MobileException.StorageException::class.java) { engine.hasIdentity() }
    }

    @Test
    fun aCopyPutBackWithoutAShredStillOpens() {
        aliceWithACopyOfHerFiles()

        putTheCopyBack()

        assertTrue(open().appEngine.hasIdentity())
    }
}
