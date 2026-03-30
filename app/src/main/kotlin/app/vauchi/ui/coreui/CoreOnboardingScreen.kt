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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Core-driven onboarding screen that replaces the hardcoded step views.
 *
 * Uses [OnboardingViewModel] to get [ScreenModel] state from the core
 * [MobileOnboardingWorkflow] and renders it via [ScreenRenderer].
 *
 * This composable lives in the `binding-dependent` source set because it
 * depends on [OnboardingViewModel] which requires UniFFI bindings.
 * Once bindings are published to Maven, the app can switch from the old
 * [app.vauchi.ui.onboarding.OnboardingScreen] to this one.
 */
@Composable
fun CoreOnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val screen by viewModel.screen.collectAsState()
    val isComplete by viewModel.isComplete.collectAsState()
    val error by viewModel.error.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastUndoActionId by viewModel.toastUndoActionId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate away when onboarding completes
    LaunchedEffect(isComplete) {
        if (isComplete) {
            onComplete()
        }
    }

    // Show errors via snackbar
    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val currentScreen = screen

        if (currentScreen == null) {
            // Loading state
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
                    onAction = viewModel::handleAction,
                    modifier = Modifier.fillMaxSize(),
                    toastMessage = toastMessage,
                    toastUndoActionId = toastUndoActionId,
                    onToastDismiss = viewModel::dismissToast,
                )
            }
        }
    }
}
