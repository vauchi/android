// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.vauchi.util.LocalizationManager

/**
 * Core-driven onboarding screen rendered through the shared
 * [CoreAppViewModel] (PAE wrapper) — the same engine that drives every
 * post-identity screen.
 *
 * Slice 32c retired the `MobileOnboardingWorkflow` peer UniFFI object.
 * The OnboardingEngine state machine lives inside `AppEngine` and is
 * already driven by `PlatformAppEngine.current_screen_json` /
 * `handle_action_json` when no identity exists. This screen used to
 * instantiate its own `OnboardingViewModel` wrapping
 * `MobileOnboardingWorkflow`; that path collected `OnboardingData`
 * in memory and required the frontend to extract `display_name` and
 * call `createIdentity` on `Complete`, silently dropping
 * `selected_groups` + `fields`. The PAE path persists the full
 * `OnboardingData` atomically in core (`AppEngine::handle_completion`
 * in vauchi-app).
 *
 * See: `_private/docs/problems/2026-05-17-slice-32c-mobile-ui-retirement/`,
 * ADR-043 Amendment 2 (forthcoming).
 */
@Composable
fun CoreOnboardingScreen(
    coreAppViewModel: CoreAppViewModel,
    onIdentityCreated: () -> Unit,
) {
    val screen by coreAppViewModel.screen.collectAsState()
    // ActionResult.ShowToast host: onboarding renders its own ScreenRenderer
    // (not the main CoreScreenView), so the toast state must be threaded in
    // here too — otherwise a core-emitted toast sets toastMessage with nothing
    // observing it and is silently dropped.
    val toastMessage by coreAppViewModel.toastMessage.collectAsState()
    val toastUndoActionId by coreAppViewModel.toastUndoActionId.collectAsState()

    // Cold start: ensure PAE's current screen is loaded so the user
    // sees the Onboarding step PAE reports. With no identity, that's
    // `AppScreen::Onboarding` → `OnboardingEngine::current_screen()` →
    // IdentityCheck / DefaultName / etc.
    LaunchedEffect(Unit) {
        coreAppViewModel.loadScreen()
    }

    // PAE transitions away from onboarding — identity has been written
    // to the DB by `AppEngine::handle_completion` (display name,
    // groups, and per slice 32c S2, fields). Hand control back to
    // MainActivity so it flips app state from onboarding to ready and
    // renders the destination core already navigated to.
    // (`2026-07-06-mobile-domain-shell-violations` A13).
    LaunchedEffect(Unit) {
        coreAppViewModel.onboardingCompleteEvent.collect {
            onIdentityCreated()
        }
    }

    // restore_backup on `link_choice` emits Command::FilePickFromUser;
    // without a host-side handler the action is a silent no-op
    // (2026-06-11-android-restore-paths-all-dead).
    FilePickHandler(coreAppViewModel)

    // Restore failures arrive as ActionResult.ShowAlert; without a
    // host the error is swallowed and the user is silently bounced
    // back to link_choice (2026-06-11-android-restore-paths-all-dead).
    val alertMessage by coreAppViewModel.alertMessage.collectAsState()
    alertMessage?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { coreAppViewModel.dismissAlert() },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { coreAppViewModel.dismissAlert() }) {
                    Text(LocalizationManager.getInstance(LocalContext.current).t("action.ok"))
                }
            },
        )
    }

    Scaffold { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            val currentScreen = screen

            if (currentScreen == null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    },
                    contentKey = { it.screenId },
                    label = "core_onboarding_screen",
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) { targetScreen ->
                    ScreenRenderer(
                        screen = targetScreen,
                        onAction = coreAppViewModel::handleAction,
                        modifier = Modifier.fillMaxSize(),
                        toastMessage = toastMessage,
                        toastUndoActionId = toastUndoActionId,
                        onToastDismiss = coreAppViewModel::dismissToast,
                    )
                }
            }

            ActionInFlightOverlay(coreAppViewModel)
        }
    }
}

