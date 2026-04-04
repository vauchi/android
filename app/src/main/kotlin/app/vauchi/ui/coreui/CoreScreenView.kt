// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.vauchi.ui.coreui.components.ToastOverlay

/**
 * Generic composable that renders any core-driven screen via [CoreAppViewModel].
 *
 * Uses the shared [CoreAppViewModel] — all instances share one PlatformAppEngine
 * (one DB connection, one engine cache). When this composable enters composition,
 * it navigates the engine to [screenName]. Engine caching makes tab switches instant.
 *
 * Usage:
 * ```kotlin
 * CoreScreenView(viewModel = coreAppViewModel, screenName = "Groups")
 * CoreScreenView(viewModel = coreAppViewModel, screenName = "Settings")
 * ```
 */
@Composable
fun CoreScreenView(
    viewModel: CoreAppViewModel,
    screenName: String,
    modifier: Modifier = Modifier,
) {
    val screen by viewModel.screen.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastUndoActionId by viewModel.toastUndoActionId.collectAsState()
    val alertMessage by viewModel.alertMessage.collectAsState()

    var currentScreen by remember { mutableStateOf<String?>(null) }

    // Navigate to screenName when it changes (or on first composition)
    LaunchedEffect(screenName) {
        if (currentScreen != screenName) {
            currentScreen = screenName
            viewModel.navigateTo(screenName)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentScreenModel = screen
        if (currentScreenModel != null) {
            ScreenRenderer(
                screen = currentScreenModel,
                onAction = { action -> viewModel.handleAction(action) },
                toastMessage = toastMessage,
                toastUndoActionId = toastUndoActionId,
                onToastDismiss = { viewModel.dismissToast() },
                onToastUndo = { actionId ->
                    viewModel.handleAction(
                        UserAction.UndoPressed(actionId),
                    )
                    viewModel.dismissToast()
                },
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Alert dialog
        alertMessage?.let { (title, message) ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissAlert() },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissAlert() }) {
                        Text("OK")
                    }
                },
            )
        }
    }
}
