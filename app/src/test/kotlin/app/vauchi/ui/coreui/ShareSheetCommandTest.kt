// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vauchi.data.OldStorageKeyProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.data.VauchiRepository
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * The link-exchange Share button: Core emits `Command::ShowShareSheet`
 * with the link and moves on to waiting for the response, so the view
 * model must hand the URL to the activity for the system share chooser
 * (vauchi/private#272: before this the command fell through to the NFC
 * catch-all and a debug log).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class ShareSheetCommandTest {
    private lateinit var context: Context
    private val engines = mutableListOf<PlatformAppEngine>()

    @Before
    fun setUp() {
        HostCoreLibrary.assumePresentInSharedSandbox()
        FakeAndroidKeyStore.install()
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
    }

    @After
    fun tearDown() {
        engines.forEach(PlatformAppEngine::close)
        Dispatchers.resetMain()
    }

    @Test
    fun aShowShareSheetCommandHandsTheUrlToTheActivity() {
        val engine = VauchiRepository(context, OldStorageKeyProvider()).appEngine.also(engines::add)
        val viewModel = CoreAppViewModel(appEngine = engine)
        assertNull(viewModel.shareUrlRequest.value)

        viewModel.handleExchangeCommands(
            listOf(CommandDTO.ShowShareSheet("https://vauchi.app/x#pk=abc&n=def")),
        )

        assertEquals("https://vauchi.app/x#pk=abc&n=def", viewModel.shareUrlRequest.value)

        viewModel.consumeShareUrlRequest()

        assertNull(viewModel.shareUrlRequest.value)
    }
}
