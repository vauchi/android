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
 * Reachability guards: every screen tree must host the modal-class
 * `ActionResult`s — `ShowAlert` and `ShowToast` — goal 2 of
 * `2026-06-11-silent-failure-mode-umbrella`.
 *
 * A [CoreAppViewModel] state-flip test cannot catch a missing host: the VM
 * flips its state identically whether or not a host observes it, and the
 * message is silently dropped (the shape of every bug under that umbrella).
 * These guards drive the real composables with a real (no-identity) engine
 * through [CoreAppViewModel.applyResult] — the exact arm the action
 * dispatch path uses — and assert the probe text reaches the rendered
 * hierarchy.
 *
 * Covered cells: main × ShowToast, main × ShowAlert,
 * onboarding × ShowAlert. The onboarding × ShowToast cell lives in
 * [CoreOnboardingToastHostTest].
 */
class ActionResultHostTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var tempDir: File
    private lateinit var repository: VauchiRepository
    private lateinit var viewModel: CoreAppViewModel

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "action_result_host_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        repository = VauchiRepository(TestContextWrapper(context, tempDir), TestStorageKeyProvider())
        viewModel = CoreAppViewModel(repository.appEngine, NoopNfcReader, NoopNfcResponder)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun main_tree_hosts_show_toast() {
        mountMainTree()

        applyOnUi(ActionResult.ShowToast(message = TOAST_PROBE, undoActionId = null))

        composeTestRule
            .onNodeWithText(TOAST_PROBE)
            .assertExists(
                "Main tree dropped ActionResult.ShowToast — no toast host " +
                    "observes CoreAppViewModel.toastMessage in CoreScreenView",
            )
    }

    @Test
    fun main_tree_hosts_show_alert() {
        mountMainTree()

        applyOnUi(ActionResult.ShowAlert(title = ALERT_TITLE_PROBE, message = ALERT_BODY_PROBE))

        composeTestRule
            .onNodeWithText(ALERT_TITLE_PROBE)
            .assertExists(
                "Main tree dropped ActionResult.ShowAlert — no alert host " +
                    "observes CoreAppViewModel.alertMessage in CoreScreenView",
            )
        composeTestRule.onNodeWithText(ALERT_BODY_PROBE).assertExists(
            "Main tree alert host rendered the title but not the message body",
        )
    }

    @Test
    fun onboarding_tree_hosts_show_alert() {
        composeTestRule.setContent {
            VauchiTheme {
                CoreOnboardingScreen(coreAppViewModel = viewModel, onIdentityCreated = {})
            }
        }
        awaitScreenLoaded()

        applyOnUi(ActionResult.ShowAlert(title = ALERT_TITLE_PROBE, message = ALERT_BODY_PROBE))

        composeTestRule
            .onNodeWithText(ALERT_TITLE_PROBE)
            .assertExists(
                "Onboarding tree dropped ActionResult.ShowAlert — no alert host " +
                    "observes CoreAppViewModel.alertMessage in CoreOnboardingScreen",
            )
        composeTestRule.onNodeWithText(ALERT_BODY_PROBE).assertExists(
            "Onboarding alert host rendered the title but not the message body",
        )
    }

    /**
     * Mounts [CoreScreenView] and loads the engine's current screen.
     * CoreScreenView renders only `viewModel.screen` (dispatch inversion —
     * it never navigates), so loading is the test's job; with no identity
     * the engine reports an onboarding ScreenModel, which is enough to
     * mount the ScreenRenderer the toast host threads through.
     */
    private fun mountMainTree() {
        composeTestRule.setContent {
            VauchiTheme {
                CoreScreenView(viewModel = viewModel, screenName = "host_guard")
            }
        }
        viewModel.loadScreen()
        awaitScreenLoaded()
    }

    private fun awaitScreenLoaded() {
        composeTestRule.waitUntil(SCREEN_LOAD_TIMEOUT_MS) {
            viewModel.screen.value != null
        }
    }

    private fun applyOnUi(result: ActionResult) {
        composeTestRule.runOnUiThread { viewModel.applyResult(result) }
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val TOAST_PROBE = "Toast host probe 7f3e"
        const val ALERT_TITLE_PROBE = "Alert host probe 7f3e"
        const val ALERT_BODY_PROBE = "Alert body probe 7f3e"
        const val SCREEN_LOAD_TIMEOUT_MS = 10_000L
    }
}
