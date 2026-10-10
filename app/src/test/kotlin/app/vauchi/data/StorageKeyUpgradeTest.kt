// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import app.vauchi.ui.coreui.CoreAppViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.PlatformAppEngine
import java.io.File
import java.security.KeyStoreException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An install from before vauchi/private#580 upgrades through the real Core,
 * as on the rig's S7: the old key reads, the keychain's own key may need an
 * unlock before it takes a write.
 *
 * Traces to: features/storage_key_upgrade.feature
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class StorageKeyUpgradeTest {
    private lateinit var context: Context
    private lateinit var provider: OldStorageKeyProvider
    private var keychainFailure: Exception? = null
    private val keychainKey: SecretKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")

    /**
     * Engines this test opened; closed after it, so no listener a view
     * model registered on them is still called while the JVM exits.
     */
    private val engines = mutableListOf<PlatformAppEngine>()

    @Before
    fun setUp() {
        HostCoreLibrary.assumePresentInSharedSandbox()
        FakeAndroidKeyStore.install()
        provider = OldStorageKeyProvider()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.filesDir
            .listFiles()
            .orEmpty()
            .forEach { it.deleteRecursively() }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
        installFromBefore(context, provider) { engine ->
            engine.dispatchDomainCommand(DomainCommand.CreateIdentity("Before"))
            engine.dispatchDomainCommand(DomainCommand.ImportContactsFromVcf(IMPORTED_CONTACT.toByteArray()))
        }
    }

    @After
    fun tearDown() {
        engines.forEach(PlatformAppEngine::close)
        Dispatchers.resetMain()
    }

    private fun open(): PlatformAppEngine {
        val bridge =
            PlatformKeychainBridge(context, PreferencesLegacyStorageKey(context, provider)) {
                keychainFailure?.let { throw it }
                keychainKey
            }
        return PlatformAppEngine.openWithKeychain(context.filesDir.absolutePath, "https://relay.test", null, bridge).also(engines::add)
    }

    private fun surface(engine: PlatformAppEngine): String {
        val batch = engine.initialCommandsJson()
        return Regex("\"surface_id\":\"([^\"]+)\"").find(batch)?.groupValues?.get(1) ?: batch
    }

    private fun contactCount(engine: PlatformAppEngine): UInt =
        (engine.dispatchDomainCommand(DomainCommand.ContactCount) as DomainCommandResult.Count).value

    private fun keychainNames(): List<String> = File(context.filesDir, "keychain").list().orEmpty().sorted()

    private fun oldKeyBlob(): String? =
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PreferencesLegacyStorageKey.PREF_ENCRYPTED_STORAGE_KEY, null)

    @Test
    fun theUpgradeKeepsEverythingAndLeavesOnlyCoresKey() {
        val engine = open()

        assertTrue(!surface(engine).startsWith("storage_lock"))
        assertTrue(engine.hasIdentity())
        assertEquals(1u, contactCount(engine))
        assertEquals(listOf("smk"), keychainNames())
        assertNull(oldKeyBlob())
    }

    @Test
    fun aKeychainThatNeedsAnUnlockStartsOnCoresUnlockScreenAndAsksForThePrompt() {
        keychainFailure = UserNotAuthenticatedException()
        val engine = open()

        // The app's first batch carries Core's one automatic prompt request.
        val viewModel = CoreAppViewModel(appEngine = engine)
        assertEquals(true, runBlocking { withTimeout(30_000) { viewModel.biometricUnlockRequest.first { it != null } } })
        assertEquals("storage_lock.locked", surface(engine))
        assertEquals(emptyList<String>(), keychainNames())
        assertNotNull(oldKeyBlob())
    }

    @Test
    fun unlockingFinishesTheUpgrade() {
        keychainFailure = UserNotAuthenticatedException()
        val engine = open()
        val opened = CompletableDeferred<Unit>()
        val viewModel =
            CoreAppViewModel(
                appEngine = engine,
                onPresentationCommitted = { if (runCatching { engine.hasIdentity() }.isSuccess) opened.complete(Unit) },
            )
        runBlocking { withTimeout(30_000) { viewModel.biometricUnlockRequest.first { it != null } } }

        keychainFailure = null
        viewModel.handleBiometricUnlockSucceeded()

        runBlocking { withTimeout(30_000) { opened.await() } }
        assertTrue(engine.hasIdentity())
        assertEquals(1u, contactCount(engine))
        assertEquals(listOf("smk"), keychainNames())
        assertNull(oldKeyBlob())
    }

    @Test
    fun aKeychainThatFailsForAnotherReasonOffersTryAgain() {
        keychainFailure = KeyStoreException("keystore did not answer")
        val engine = open()

        assertEquals("storage_lock.unavailable", surface(engine))
        assertEquals(emptyList<String>(), keychainNames())
        assertNotNull(oldKeyBlob())
    }

    private companion object {
        const val IMPORTED_CONTACT = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:before-580\r\nFN:Carol\r\nEND:VCARD\r\n"
    }
}
