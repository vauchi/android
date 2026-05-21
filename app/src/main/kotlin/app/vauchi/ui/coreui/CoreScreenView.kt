// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

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
    /**
     * When `true` (default), the LaunchedEffect on mount calls
     * `viewModel.navigateTo(screenName)`. Set to `false` when the
     * caller has already navigated the engine to this screen — passing
     * `true` in that case pushes the destination onto `nav_history`
     * twice, and the next `navigateBack()` pops the destination itself
     * and lands the user on the same screen. See problem record
     * `2026-05-21-android-back-stack-and-bottom-nav-broken`.
     */
    navigateOnMount: Boolean = true,
) {
    val screen by viewModel.screen.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastUndoActionId by viewModel.toastUndoActionId.collectAsState()
    val alertMessage by viewModel.alertMessage.collectAsState()
    val imagePickEvent by viewModel.imagePickEvent.collectAsState()
    val useFrontCamera by viewModel.useFrontCamera.collectAsState()

    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf<String?>(null) }

    // Image picker launcher (library)
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        viewModel.handleImageReceived(bytes)
                    } else {
                        viewModel.handleImagePickCancelled()
                    }
                } catch (_: Exception) {
                    viewModel.handleImagePickCancelled()
                }
            } else {
                viewModel.handleImagePickCancelled()
            }
        }

    // Camera capture launcher
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicture(),
        ) { success ->
            if (success && cameraImageUri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(cameraImageUri!!)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        viewModel.handleImageReceived(bytes)
                    } else {
                        viewModel.handleImagePickCancelled()
                    }
                } catch (_: Exception) {
                    viewModel.handleImagePickCancelled()
                }
            } else {
                viewModel.handleImagePickCancelled()
            }
        }

    // Observe image pick events from core
    LaunchedEffect(imagePickEvent) {
        when (imagePickEvent) {
            "library" -> {
                viewModel.consumeImagePickEvent()
                imagePickerLauncher.launch("image/*")
            }

            "camera" -> {
                viewModel.consumeImagePickEvent()
                val imageFile = File(context.cacheDir, "vauchi_camera_capture.jpg")
                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        imageFile,
                    )
                cameraImageUri = uri
                cameraLauncher.launch(uri)
            }

            else -> { /* consumed or null */ }
        }
    }

    // Navigate to screenName when it changes (or on first composition).
    // Skip the navigateTo when `navigateOnMount = false` — the caller
    // has already navigated and a second call would double-push to
    // `nav_history`, breaking `navigateBack()`.
    LaunchedEffect(screenName) {
        if (currentScreen != screenName) {
            currentScreen = screenName
            if (navigateOnMount) {
                viewModel.navigateTo(screenName)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentScreenModel = screen
        if (currentScreenModel != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalUseFrontCamera provides useFrontCamera,
            ) {
                ScreenRenderer(
                    screen = currentScreenModel,
                    onAction = { action -> viewModel.handleAction(action) },
                    toastMessage = toastMessage,
                    toastUndoActionId = toastUndoActionId,
                    onToastDismiss = { viewModel.dismissToast() },
                )
            }
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
