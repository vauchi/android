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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.vauchi.ui.coreui.CoreAppViewModel
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

        Scaffold(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        val gesture =
                            when {
                                event.key == Key.Escape -> {
                                    ShortcutGesture.Back
                                }

                                event.isCtrlPressed && event.key == Key.K -> {
                                    ShortcutGesture.Navigation
                                }

                                event.isCtrlPressed && event.key == Key.Enter -> {
                                    ShortcutGesture.Primary
                                }

                                event.isAltPressed && event.key == Key.DirectionDown -> {
                                    ShortcutGesture.Secondary
                                }

                                event.isCtrlPressed && event.key == Key.Z -> {
                                    ShortcutGesture.Undo
                                }

                                else -> {
                                    null
                                }
                            }
                        val action =
                            gesture?.let { contextualShortcut(state.activeBar, it) }
                                ?: return@onPreviewKeyEvent false
                        viewModel.activateAndDispatch(
                            activeSurfaceId,
                            PresentationEvent.ActionActivated(
                                surfaceId = activeSurfaceId,
                                interactionId = action.interactionId,
                            ),
                        )
                        true
                    },
            snackbarHost = {
                SnackbarHost(snackbarHostState)
            },
            bottomBar = {
                Box(
                    modifier =
                        Modifier
                            .padding(
                                horizontal =
                                    if (profile.windowClass == WindowClass.Compact) {
                                        0.dp
                                    } else {
                                        24.dp
                                    },
                                vertical =
                                    if (profile.windowClass == WindowClass.Compact) {
                                        0.dp
                                    } else {
                                        12.dp
                                    },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    ContextCommandBar(
                        surfaceId = activeSurfaceId,
                        bar = state.activeBar,
                        windowClass = profile.windowClass,
                        onEvent = {
                            viewModel.activateAndDispatch(activeSurfaceId, it)
                        },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                CompositionLocalProvider(LocalUseFrontCamera provides useFrontCamera) {
                    if (profile.paneLayout == PaneLayout.Split) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            state.surfaces[profile.primarySurface]?.let { surface ->
                                SurfaceHost(
                                    surface = surface,
                                    active = surface.surfaceId == activeSurfaceId,
                                    viewModel = viewModel,
                                    focusedBindingId = focusedBindingId,
                                    onFocusedBinding = onFocusedBinding,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            profile.detailSurface
                                ?.let(state.surfaces::get)
                                ?.let { surface ->
                                    SurfaceHost(
                                        surface = surface,
                                        active = surface.surfaceId == activeSurfaceId,
                                        viewModel = viewModel,
                                        focusedBindingId = focusedBindingId,
                                        onFocusedBinding = onFocusedBinding,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                        }
                    } else {
                        state.surfaces[activeSurfaceId]?.let { surface ->
                            SurfaceHost(
                                surface = surface,
                                active = true,
                                viewModel = viewModel,
                                focusedBindingId = focusedBindingId,
                                onFocusedBinding = onFocusedBinding,
                            )
                        }
                    }
                }

                state.activeOverlay?.let { overlay ->
                    PresentationOverlay(
                        overlay = overlay,
                        windowClass = profile.windowClass,
                        reducedMotion = reducedMotion,
                        onAction = {
                            viewModel.activateAndDispatch(overlay.surfaceId, it)
                        },
                        onDismiss = viewModel::dismissPresentationOverlay,
                    )
                }

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
            }
        }
    }
}

@Composable
private fun SurfaceHost(
    surface: SurfaceSpec,
    active: Boolean,
    viewModel: CoreAppViewModel,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PresentationSurface(
        surface = surface,
        active = active,
        onActivate = {
            viewModel.dispatchPresentation(
                PresentationEvent.SurfaceActivated(surface.surfaceId),
            )
        },
        onEvent = {
            viewModel.activateAndDispatch(surface.surfaceId, it)
        },
        onCameraPermissionDenied = viewModel::onCameraPermissionDenied,
        focusedBindingId = focusedBindingId,
        onFocusedBinding = onFocusedBinding,
        modifier = modifier,
    )
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
