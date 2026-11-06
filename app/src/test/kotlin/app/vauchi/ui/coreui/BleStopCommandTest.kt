// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vauchi.ble.BleCommand
import app.vauchi.data.OldStorageKeyProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.data.VauchiRepository
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * The recovery link ends by withdrawing its advertisement or scan. Until
 * private#796 both commands decoded as `Unknown` and the radio stayed on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class BleStopCommandTest {
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
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
    }

    @After
    fun tearDown() {
        engines.forEach(PlatformAppEngine::close)
        Dispatchers.resetMain()
    }

    @Test
    fun stopCommandsReachTheActivityInOrder() =
        runTest {
            val engine = VauchiRepository(context, OldStorageKeyProvider()).appEngine.also(engines::add)
            val viewModel = CoreAppViewModel(appEngine = engine)
            val received = mutableListOf<BleCommand>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.bleCommands.toList(received)
            }

            viewModel.handleExchangeCommands(
                listOf(CommandDTO.BleStopScanning, CommandDTO.BleStopAdvertising),
            )

            assertEquals(
                listOf(BleCommand.StopScanning, BleCommand.StopAdvertising),
                received,
            )
        }
}
