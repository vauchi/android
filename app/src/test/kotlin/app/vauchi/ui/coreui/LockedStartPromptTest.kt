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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Core's lock screen asks for the unlock prompt with a bare
 * `RequestBiometricUnlock` command (vauchi/private#580). Found on the rig:
 * the S7 showed Core's lock screen but never the prompt, and logged
 * "Undecoded exchange command: RequestBiometricUnlock".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class LockedStartPromptTest {
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
    fun aLockedStartAsksTheAppForTheUnlockPrompt() {
        val provider = OldStorageKeyProvider()
        installFromBefore(context, provider)
        provider.failure = AuthenticationRequiredException("unlock")
        val engine = VauchiRepository(context, provider).appEngine

        val viewModel = CoreAppViewModel(appEngine = engine)

        val requested = runBlocking { withTimeout(30_000) { viewModel.biometricUnlockRequest.first { it != null } } }
        assertEquals(true, requested)
    }
}
