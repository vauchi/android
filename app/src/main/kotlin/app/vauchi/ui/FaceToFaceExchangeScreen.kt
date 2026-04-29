// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreScreenView
import app.vauchi.ui.coreui.UserAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pair 4b of `_private/docs/problems/2026-04-28-pure-humble-ui-retire-native-screens`.
 *
 * Pure Humble UI shell — renders the multi-stage face-to-face exchange via
 * [CoreScreenView] over the core-owned `MultiStageExchangeEngine`. Per
 * ADR-021/043 this composable holds no domain state, makes no navigation
 * decisions, and references no domain types. It only:
 *
 * 1. Renders whatever core says via [CoreScreenView].
 * 2. Forwards platform-presentation hardware concerns — orientation lock,
 *    screen brightness, keep-screen-on — per ADR-031 §Hardware.
 * 3. Forwards the Android system back button to core as a UserAction.
 *    Core decides what that means; today the engine's CANCEL handler
 *    ends the cycle thread and emits a NavigateTo so the Activity's
 *    own screen-sync layer follows.
 */
@Composable
fun MultiStageExchangeScreen(coreAppViewModel: CoreAppViewModel) {
    val context = LocalContext.current

    // Lock orientation to portrait during exchange. Restored on dispose.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Keep screen on + drop brightness to 65% (max brightness overexposes
    // the scanning device's camera, washing out QR module contrast). Gray
    // QR colors emitted by core compensate for the reduced luminance.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousBrightness = activity?.window?.attributes?.screenBrightness ?: -1.0f
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            params.screenBrightness = 0.65f
            window.attributes = params
        }
        onDispose {
            activity?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val params = window.attributes
                params.screenBrightness = previousBrightness
                window.attributes = params
            }
        }
    }

    // Forward system back to core as the engine-level cancel event.
    // Core decides the next screen via its routing layer; the Activity's
    // own screen sync layer reflects the result.
    BackHandler {
        coreAppViewModel.handleAction(UserAction.ActionPressed(actionId = "cancel"))
    }

    CoreScreenView(
        viewModel = coreAppViewModel,
        screenName = "MultiStageExchange",
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Camera preview that supports switching between front and rear cameras.
 * Scans for QR codes and reports them via [onQrCodeDetected].
 *
 * Kept here (rather than retired with the multi-stage screen) because
 * `BleExchangeScreen` still consumes it. Will move out alongside the BLE
 * screen's own Pure Humble UI migration.
 *
 * @param showPreview When true, renders a visible camera preview (for the
 *   small preview square). When false, uses a 0dp invisible anchor.
 */
@Composable
fun FaceToFaceCameraPreview(
    useFrontCamera: Boolean,
    showPreview: Boolean = false,
    onQrCodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView =
                if (showPreview) {
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                } else {
                    null
                }

            val rootView = previewView ?: android.view.View(ctx)

            rootView.post {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    // 240p optimal for rxing on Samsung S7 — 9ms decode,
                    // 100% scan rate on animated V4 QR. 320x240 with the
                    // CLOSEST_LOWER_THEN_HIGHER fallback rule lets newer
                    // devices pick the closest supported resolution.
                    val resolutionSelector =
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(320, 240),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            ).build()

                    val imageAnalyzer =
                        ImageAnalysis
                            .Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(
                                    cameraExecutor,
                                    QrCodeAnalyzer(
                                        onQrCodeDetected = { code -> onQrCodeDetected(code) },
                                        isFrontCamera = useFrontCamera,
                                    ),
                                )
                            }

                    val cameraSelector =
                        if (useFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                    try {
                        cameraProvider.unbindAll()

                        val useCases =
                            if (showPreview && previewView != null) {
                                val preview =
                                    Preview
                                        .Builder()
                                        .setResolutionSelector(resolutionSelector)
                                        .build()
                                        .also { it.surfaceProvider = previewView.surfaceProvider }
                                arrayOf(preview, imageAnalyzer)
                            } else {
                                arrayOf(imageAnalyzer)
                            }

                        val camera =
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                *useCases,
                            )
                        // Trigger center auto-focus
                        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val action =
                            FocusMeteringAction
                                .Builder(factory.createPoint(0.5f, 0.5f))
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                        camera.cameraControl.startFocusAndMetering(action)
                    } catch (e: Exception) {
                        Log.e("Vauchi", "Exchange: camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }

            rootView
        },
        modifier =
            if (showPreview) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(0.dp)
            },
    )
}
