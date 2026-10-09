// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vauchi.data.AuthenticationRequiredException
import app.vauchi.data.OldStorageKeyProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.data.VauchiRepository
import app.vauchi.data.installFromBefore
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.DomainCommand

/**
 * The unlock prompt on Core's lock screen also unlocks the app: when a duress
 * PIN is set up, Core answers the prompt with an app-password requirement
 * (ADR-032), and the app must hear it rather than drop it, or the person
 * lands in normal mode without being asked (vauchi/private#580).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class UnlockAuthenticationRequirementTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        HostCoreLibrary.assumePresentInSharedSandbox()
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unlockingAnInstallWithADuressPinTellsTheAppToAskForTheAppPassword() {
        val provider = OldStorageKeyProvider()
        installFromBefore(context, provider) { engine ->
            engine.dispatchDomainCommand(DomainCommand.CreateIdentity("Test User"))
            engine.dispatchDomainCommand(DomainCommand.SetupAppPassword("123456"))
            engine.dispatchDomainCommand(DomainCommand.SetupDuressPassword("654321"))
        }
        provider.failure = AuthenticationRequiredException("unlock")
        val engine = VauchiRepository(context, provider).appEngine
        val requirement = CompletableDeferred<String>()
        val viewModel =
            CoreAppViewModel(appEngine = engine, onAuthenticationRequirement = { requirement.complete(it) })
        provider.failure = null

        viewModel.handleBiometricUnlockSucceeded()

        assertEquals("app_password", runBlocking { withTimeout(30_000) { requirement.await() } })
    }
}
