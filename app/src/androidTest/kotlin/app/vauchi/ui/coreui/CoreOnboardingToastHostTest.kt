// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import app.vauchi.data.TestContextWrapper
import app.vauchi.data.TestStorageKeyProvider
import app.vauchi.data.VauchiRepository
import app.vauchi.ui.theme.VauchiTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Reachability guard: the onboarding screen tree must host
 * `ActionResult.ShowToast`. Onboarding renders through its own
 * [ScreenRenderer] call (not the main [CoreScreenView]), so the toast wiring
 * has to be threaded in separately — when it was missing, a core-emitted
 * toast set [CoreAppViewModel.toastMessage] but nothing observed it and the
 * message was silently dropped (twin of the iOS main-tree gap; silent-failure
 * umbrella under `2026-06-11-store-submission-blockers`).
 *
 * A [CoreAppViewModel] state-flip test cannot catch this: the VM flips
 * `toastMessage` identically whether or not a host observes it. The contract
 * worth pinning is that the *rendered* onboarding hierarchy surfaces the toast
 * text, so this drives the real composable with a real (no-identity →
 * onboarding) engine.
 */
class CoreOnboardingToastHostTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: File
    private lateinit var repository: VauchiRepository
    private lateinit var viewModel: CoreAppViewModel

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "toast_host_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        // No createIdentity: PAE reports onboarding screens — the tree whose
        // missing toast host this guards.
        repository = VauchiRepository(TestContextWrapper(context, tempDir), TestStorageKeyProvider())
        viewModel = CoreAppViewModel(repository.appEngine, NoopNfcReader, NoopNfcResponder)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun onboarding_tree_hosts_show_toast() {
        composeTestRule.setContent {
            VauchiTheme {
                CoreOnboardingScreen(coreAppViewModel = viewModel, onIdentityCreated = {})
            }
        }

        // CoreOnboardingScreen's LaunchedEffect calls loadScreen(); wait until
        // the onboarding screen is present so its ScreenRenderer is mounted.
        composeTestRule.waitUntil(SCREEN_LOAD_TIMEOUT_MS) {
            viewModel.screen.value != null
        }

        // Emit the toast exactly as the ActionResult.ShowToast path does.
        composeTestRule.runOnUiThread { viewModel.showToast(TOAST_PROBE) }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(TOAST_PROBE)
            .assertExists(
                "Onboarding tree dropped ActionResult.ShowToast — no toast host " +
                    "observes CoreAppViewModel.toastMessage in CoreOnboardingScreen",
            )
    }

    private companion object {
        const val TOAST_PROBE = "Toast host probe a91c"
        const val SCREEN_LOAD_TIMEOUT_MS = 10_000L
    }
}
