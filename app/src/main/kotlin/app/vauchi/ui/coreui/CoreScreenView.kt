// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.ui.theme.LocalStatusColors
import app.vauchi.util.LocalizationManager
import app.vauchi.util.applyLocaleFromUserAction
import kotlinx.coroutines.delay
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
    // Render-target label (kept for call-site readability). CoreScreenView no
    // longer navigates — core is already on the screen by the time it renders
    // (dispatch inversion); it only renders viewModel.screen.
    @Suppress("UNUSED_PARAMETER") screenName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val screen by viewModel.screen.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastUndoActionId by viewModel.toastUndoActionId.collectAsState()
    val alertMessage by viewModel.alertMessage.collectAsState()
    val imagePickEvent by viewModel.imagePickEvent.collectAsState()
    val useFrontCamera by viewModel.useFrontCamera.collectAsState()
    val celebrateRequest by viewModel.celebrateRequest.collectAsState()
    val ahaMomentRequest by viewModel.ahaMomentRequest.collectAsState()
    val reduceMotion = rememberReduceMotion()

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

    FilePickHandler(viewModel)

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

    // M2 S5 exchange-success ceremony: haptic always fires when a request
    // arrives. Animation is handled by the overlay below.
    val view = LocalView.current
    LaunchedEffect(celebrateRequest) {
        celebrateRequest?.let {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                },
            )
        }
    }

    // Show the aha-moment toast when no celebrate animation is playing.
    // The animation itself carries the moment, so we skip the duplicate
    // toast until it (or the no-animation request) is consumed.
    LaunchedEffect(ahaMomentRequest, celebrateRequest) {
        ahaMomentRequest?.let { moment ->
            if (celebrateRequest == null) {
                viewModel.consumeAhaMomentRequest()
                viewModel.showToast(moment.message)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentScreenModel = screen
        if (currentScreenModel != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalUseFrontCamera provides useFrontCamera,
            ) {
                val context = LocalContext.current
                ScreenRenderer(
                    screen = currentScreenModel,
                    onAction = { action ->
                        applyLocaleFromUserAction(context, action)
                        viewModel.handleAction(action)
                    },
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

        ActionInFlightOverlay(viewModel)

        // M2 S5 exchange-success animation overlay. Consume the request even
        // when no animation plays so it never outlives the moment.
        celebrateRequest?.let { request ->
            if (!reduceMotion && request.animation != "none") {
                CelebrateOverlay(
                    onAnimationEnd = { viewModel.consumeCelebrateRequest() },
                )
            } else {
                viewModel.consumeCelebrateRequest()
            }
        }

        // Alert dialog
        alertMessage?.let { (title, message) ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissAlert() },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissAlert() }) {
                        Text(localizationManager.t("action.ok"))
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val appPrefs = context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val appReduceMotion = appPrefs.getBoolean(VauchiPreferences.KEY_REDUCE_MOTION, false)
        val systemReduceMotion =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        appReduceMotion || systemReduceMotion
    }
}

/**
 * One-beat celebration overlay: a spring-scale checkmark that holds for
 * ~600 ms and then stills. Mirrors iOS `CelebrateOverlayView`.
 */
@Composable
private fun CelebrateOverlay(onAnimationEnd: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.2f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "celebrate_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "celebrate_alpha",
    )

    LaunchedEffect(Unit) {
        visible = true
        kotlinx.coroutines.delay(600)
        onAnimationEnd()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = LocalStatusColors.current.success,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .scale(scale)
                    .alpha(alpha),
        )
    }
}
