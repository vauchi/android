// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.BiometricUnlockHandler
import app.vauchi.ui.coreui.FilePickHandler
import app.vauchi.ui.coreui.LocalUseFrontCamera
import java.io.File
import kotlin.math.roundToInt

@Composable
fun PresentationHost(
    viewModel: CoreAppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.presentationState.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val alertMessage by viewModel.alertMessage.collectAsState()
    val imagePickEvent by viewModel.imagePickEvent.collectAsState()
    val useFrontCamera by viewModel.useFrontCamera.collectAsState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri.readBytesOrNull(context)?.let(viewModel::handleImageReceived)
                ?: viewModel.handleImagePickCancelled()
        }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var focusedBindingId by rememberSaveable { mutableStateOf<String?>(null) }
    val onFocusedBinding: (String, Boolean) -> Unit = { bindingId, focused ->
        focusedBindingId =
            rememberFocusedBinding(
                current = focusedBindingId,
                bindingId = bindingId,
                focused = focused,
            )
    }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val bytes =
                if (success) {
                    cameraImageUri.readBytesOrNull(context)
                } else {
                    null
                }
            bytes?.let(viewModel::handleImageReceived)
                ?: viewModel.handleImagePickCancelled()
        }
    val reducedMotion =
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)

    FilePickHandler(viewModel)
    BiometricUnlockHandler(viewModel)

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissToast()
        }
    }

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
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value.roundToInt()
        val height = maxHeight.value.roundToInt()
        val inputModes = inputModes(configuration)

        LaunchedEffect(width, height, inputModes, reducedMotion) {
            viewModel.dispatchPresentation(
                PresentationEvent.environmentChanged(
                    width = width,
                    height = height,
                    inputModes = inputModes,
                    reducedMotion = reducedMotion,
                ),
            )
        }

        val profile = state.profile
        val activeSurfaceId = state.activeSurfaceId
        BackHandler(enabled = activeSurfaceId != null) {
            viewModel.dispatchPresentation(
                PresentationEvent.BackRequested(activeSurfaceId ?: return@BackHandler),
            )
        }

        if (profile == null || activeSurfaceId == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@BoxWithConstraints
        }

        val actions =
            remember(viewModel) {
                PresentationScreenActions(
                    onEvent = viewModel::activateAndDispatch,
                    onSurfaceActivated = { surfaceId ->
                        viewModel.dispatchPresentation(PresentationEvent.SurfaceActivated(surfaceId))
                    },
                    onCameraPermissionDenied = viewModel::onCameraPermissionDenied,
                    onDismissOverlay = viewModel::dismissPresentationOverlay,
                )
            }
        CompositionLocalProvider(LocalUseFrontCamera provides useFrontCamera) {
            PresentationScreen(
                state = state,
                profile = profile,
                activeSurfaceId = activeSurfaceId,
                reducedMotion = reducedMotion,
                focusedBindingId = focusedBindingId,
                onFocusedBinding = onFocusedBinding,
                actions = actions,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                dialogs = {
                    alertMessage?.let { (title, message) ->
                        AlertDialog(
                            onDismissRequest = viewModel::dismissAlert,
                            title = { Text(title) },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = viewModel::dismissAlert) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}

private fun inputModes(configuration: Configuration): List<InputMode> =
    buildList {
        add(InputMode.Touch)
        if (configuration.keyboard != Configuration.KEYBOARD_NOKEYS) {
            add(InputMode.Keyboard)
        }
        val hasPointer =
            InputDevice
                .getDeviceIds()
                .asSequence()
                .mapNotNull(InputDevice::getDevice)
                .any { device ->
                    device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                        device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                }
        if (hasPointer) {
            add(InputMode.Pointer)
        }
    }

private fun Uri?.readBytesOrNull(context: android.content.Context): ByteArray? =
    this?.let { uri ->
        runCatching {
            context.contentResolver
                .openInputStream(uri)
                ?.use { it.readBytes() }
        }.getOrNull()
    }
