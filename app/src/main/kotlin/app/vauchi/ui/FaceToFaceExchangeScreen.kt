// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.ui.components.QrCodeAnalyzer
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
fun MultiStageExchangeScreen(
    coreAppViewModel: CoreAppViewModel,
    onCoreNavigatedAway: () -> Unit = {},
) {
    // Orientation + brightness + KEEP_SCREEN_ON now live in core
    // (Phase 2b/2c of `2026-05-04-exchange-command-screen-presentation`):
    // `MultiStageExchangeEngine::screen_entered` emits
    // `Command::SetScreenBrightness(Some(0.65))` +
    // `Command::SetIdleTimerDisabled(disabled: true)` +
    // `Command::SetOrientationLock(Some(Portrait))` on screen entry,
    // `screen_exited` emits the inverse triple. `CoreAppViewModel`
    // surfaces them via the `brightnessRequest`,
    // `idleTimerDisabledRequest`, and `orientationLockRequest`
    // StateFlows; the Activity-side collector in `MainScreen` owns
    // `Window.attributes.screenBrightness`, `FLAG_KEEP_SCREEN_ON`, and
    // `Activity.requestedOrientation`, including the snapshot/restore
    // semantics.

    // Follow core off this screen. Cancel (button or system back) routes
    // through the engine's `navigate_back`, which changes core's screen —
    // but this native shell lives in the Activity's local `Screen` enum,
    // not the `CoreScreenView` dispatch (`coreScreenIdToVariant` returns
    // null for `exchange_*`), so without this the local enum stays pinned
    // and the screen looks frozen: Cancel/Back appear dead (Bug 2,
    // `2026-05-30-exchange-screen-nav-visual-bugs`). Mirrors iOS's
    // `FaceToFaceCoreShell.onChange { dismiss() }`. The latch guards the
    // entry race where this mounts before core reaches its screen.
    val coreScreen by coreAppViewModel.screen.collectAsState()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(coreScreen?.screenId) {
        val decision =
            exchangeExitDecision(
                entered = entered,
                coreScreenId = coreScreen?.screenId,
                ownScreenId = "multi_stage_exchange",
            )
        entered = decision.entered
        if (decision.shouldExit) onCoreNavigatedAway()
    }

    // Forward system back to core as the engine-level cancel event.
    // Core decides the next screen via its routing layer; the observer
    // above then follows core off this screen.
    BackHandler {
        coreAppViewModel.handleAction(UserAction.ActionPressed(actionId = "cancel"))
    }

    // Drive the multi-stage protocol while this screen is shown. The core
    // cycle thread that used to advance the machine autonomously was
    // retired in slice-32m T1.2c; post-retirement the machine only steps
    // when the frontend polls core. Without this tick the own-QR never
    // appears and the exchange stays at "Pending" (Bug 5,
    // `2026-05-30-exchange-screen-nav-visual-bugs`). The LaunchedEffect is
    // scoped to composition, so polling starts on entry and stops on exit
    // — matching the retired thread's lifetime.
    LaunchedEffect(Unit) {
        pollLoop { coreAppViewModel.tickCore() }
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
