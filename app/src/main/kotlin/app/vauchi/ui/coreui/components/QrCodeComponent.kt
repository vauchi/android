// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.ui.components.QrCodeAnalyzer
import app.vauchi.ui.coreui.LocalUseFrontCamera
import app.vauchi.ui.coreui.QrMode
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.generateQrBitmap
import java.util.concurrent.Executors

/**
 * Renders a core `Component::QrCode`.
 *
 * Display mode: encodes `data` to a QR bitmap via the rxing-backed
 * [generateQrBitmap] (Rust via UniFFI) and shows it inline. The
 * `data` string is the full payload core wants the peer to scan
 * (typically rotates every ~300 ms during multipart exchange).
 *
 * Scan mode: opens a CameraX preview with [QrCodeAnalyzer] running
 * the rxing tryHarder pipeline on the Y-plane. Each detected payload
 * is reported back to core as `UserAction.TextChanged(componentId,
 * value)` — `core/vauchi-app/src/ui/exchange_qr.rs` interprets this
 * as `QrActionOutcome::QrScanned { data }` for the ScanQr step.
 *
 * Replaces the long-standing placeholder ("QR Code" text label /
 * "Tap to Scan" no-op button) which was unimplemented when the
 * core-driven exchange flow first landed (2026-04 rendering layer).
 */
@Composable
fun QrCodeComponent(
    componentId: String,
    data: String,
    mode: QrMode,
    label: String?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (mode) {
            QrMode.Display -> {
                QrDisplay(data = data, modifier = Modifier.fillMaxWidth())
            }

            QrMode.Scan -> {
                QrScanner(
                    componentId = componentId,
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        label?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QrDisplay(
    data: String,
    modifier: Modifier = Modifier,
) {
    // Recompute the bitmap whenever core hands us new payload bytes
    // (multipart QR rotates every ~300 ms during exchange).
    val bitmap = remember(data) { generateQrBitmap(data) }

    Surface(
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Generating QR…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QrScanner(
    componentId: String,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Camera-selector preference flipped by core's `Command::SwitchCamera`
    // (see [CoreAppViewModel.useFrontCamera]). Used both as the
    // `CameraSelector` and as a `key` so flipping it recreates the
    // PreviewView + binds CameraX with the new selector.
    val useFrontCamera = LocalUseFrontCamera.current

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Surface(
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        androidx.compose.runtime.key(useFrontCamera) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView =
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // PERFORMANCE = SurfaceView when supported (default).
                        // The earlier COMPATIBLE pin used TextureView; on the
                        // Samsung S7 (Exynos 8890, Android 8) TextureView in a
                        // Compose verticalScroll Column would attach + start
                        // streaming but never paint to screen — surface stayed
                        // black. Pixel 3a (Adreno) was unaffected. Drop the
                        // pin so SurfaceView is used wherever the platform
                        // supports it; falls back to TextureView automatically
                        // when the layout transforms it (none here).
                    }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()

                        // 240p via FaceToFaceExchangeScreen pattern — rxing
                        // tryHarder hits ~9 ms decode at this resolution with
                        // 100 % rate on V4-V10 multipart QRs.
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
                                        executor,
                                        QrCodeAnalyzer(
                                            onQrCodeDetected = { code ->
                                                // Forward to core. exchange_qr.rs
                                                // pattern-matches on TextChanged with
                                                // the QR component id and routes the
                                                // payload through QrScanned.
                                                onAction(
                                                    UserAction.TextChanged(
                                                        componentId = componentId,
                                                        value = code,
                                                    ),
                                                )
                                            },
                                        ),
                                    )
                                }

                        val preview =
                            Preview
                                .Builder()
                                .setResolutionSelector(resolutionSelector)
                                .build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                if (useFrontCamera) {
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                } else {
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                },
                                preview,
                                imageAnalyzer,
                            )
                        } catch (_: Exception) {
                            // CameraX surface acquisition can fail mid-recompose
                            // (e.g., quick-resume from background). The next bind
                            // attempt picks up the new surface; nothing to log.
                        }
                    },
                    androidx.core.content.ContextCompat
                        .getMainExecutor(ctx),
                )

                previewView
            },
        )
        }
    }
}
