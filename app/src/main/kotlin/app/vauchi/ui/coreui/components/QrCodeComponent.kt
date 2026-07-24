// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.camera.CameraFailure
import app.vauchi.ui.components.PermissionRationaleDialog
import app.vauchi.ui.components.QrCodeAnalyzer
import app.vauchi.ui.components.rememberPermissionState
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.LocalUseFrontCamera
import app.vauchi.ui.coreui.QrMode
import app.vauchi.ui.coreui.UserAction
import app.vauchi.util.LocalizationManager
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
 * value)` — `core/vauchi-app/src/ui/exchange/qr.rs` interprets this
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
    a11y: A11y?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (mode) {
            QrMode.Display -> {
                QrDisplay(
                    data = data,
                    accessibilityLabel = a11y?.label ?: label,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            QrMode.Scan -> {
                QrScanner(
                    componentId = componentId,
                    accessibilityLabel = a11y?.label ?: label,
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
    accessibilityLabel: String?,
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
                contentDescription = accessibilityLabel,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = accessibilityLabel,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun QrScanner(
    componentId: String,
    accessibilityLabel: String?,
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

    // Gate the CameraX bind behind the CAMERA runtime permission. The
    // scanner previously bound the camera unconditionally, so on a fresh
    // install (permission ungranted) the preview was a dead black box with
    // no prompt and no recovery path — see
    // _private/docs/problems/2026-06-06-exchange-ritual-flow/ (B1).
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val cameraPermission =
        rememberPermissionState(
            permission = android.Manifest.permission.CAMERA,
            title = localizationManager.t("permission.camera.title"),
            rationale = localizationManager.t("permission.camera.rationale"),
            // T0.3: on a definitive camera denial, forward it to core (via the
            // sentinel ActionPressed CoreScreenView intercepts) so the exchange
            // ledger / CameraGate fails the QR leg visibly instead of leaving
            // core waiting forever.
            // TODO(HUMBLE): T/W, P1. Mints a sentinel action id for camera
            // denial. Fix: core consumes a hardware event directly.
            // (see _private problem record 2026-07-06-mobile-domain-shell-violations)
            onDenied = {
                onAction(UserAction.ActionPressed(CameraFailure.DENIED_ACTION_ID))
            },
        )
    LaunchedEffect(Unit) { cameraPermission.request() }
    PermissionRationaleDialog(cameraPermission)

    if (!cameraPermission.isGranted) {
        CameraPermissionPrompt(
            title = cameraPermission.rationaleTitle,
            rationale = cameraPermission.rationaleText,
            grantLabel = localizationManager.t("permission.camera.grant"),
            onGrant = { cameraPermission.request() },
            modifier = modifier,
        )
        return
    }

    Surface(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    accessibilityLabel?.let { contentDescription = it }
                },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        androidx.compose.runtime.key(useFrontCamera) {
            // Bind-failure overlay state: set from the bindToLifecycle
            // catch block when CameraX cannot acquire the camera (most
            // commonly the recreate-on-flip race with the previous
            // session). Scoped to the `key(useFrontCamera)` block so a
            // subsequent successful flip starts with a clean slate.
            val bindFailure = remember { mutableStateOf<String?>(null) }
            // Camera-lifecycle diagnostics (round 7,
            // investigations/2026-07-24-camera-lifecycle-socratic-synthesis):
            // pins compose/dispose of THIS scanner instance so device logs
            // discriminate composition-teardown from bind-collision deaths.
            DisposableEffect(Unit) {
                Log.i("Vauchi", "[QrCamera] scanner composed front=$useFrontCamera")
                onDispose {
                    Log.i("Vauchi", "[QrCamera] scanner DISPOSED front=$useFrontCamera")
                }
            }
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
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

                                // 480p, not 240p: the 240p bench numbers (9 ms, 100 %
                                // on V4-V10) only hold for crisp, frame-filling
                                // captures. Handheld screen-to-screen scanning adds
                                // defocus/shake; at 240p a V10 INIT QR fails to
                                // decode at blur sigma >= 1 or < 65 % frame fill,
                                // while 480p decodes through sigma 2 and 35 % fill
                                // at <= 5 ms — host repro in
                                // 2026-07-17-real-device-frontend-smoke.md (QR
                                // decode envelope).
                                val resolutionSelector =
                                    ResolutionSelector
                                        .Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                android.util.Size(640, 480),
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
                                                        // Diagnostics: the 4-char frame-type
                                                        // prefix is a public format constant
                                                        // (qr_codec.rs PREFIX_LEN), never
                                                        // payload — logging it stays inside
                                                        // logging-rules.md ("never log QR
                                                        // data"); anything else logs a generic tag.
                                                        val frameType = code.take(4)
                                                        val known =
                                                            frameType in
                                                                setOf(
                                                                    "INIT",
                                                                    "INI2",
                                                                    "IN2D",
                                                                    "INID",
                                                                    "DATA",
                                                                    "VRFY",
                                                                    "CONF",
                                                                    "RDYY",
                                                                    "COMB",
                                                                    "FAIL",
                                                                    "SHAK",
                                                                )
                                                        Log.i(
                                                            "Vauchi",
                                                            "[QrScan] decoded type=" +
                                                                (if (known) frameType else "????") +
                                                                " len=${code.length}",
                                                        )
                                                        // Forward to core. exchange/qr.rs
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
                                    Log.i(
                                        "Vauchi",
                                        "[QrCamera] unbindAll+bind start front=$useFrontCamera",
                                    )
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
                                    Log.i("Vauchi", "[QrCamera] bind OK front=$useFrontCamera")
                                    bindFailure.value = null
                                } catch (e: Exception) {
                                    // CameraX surface acquisition can fail mid-recompose
                                    // (e.g., quick-resume from background, or — the
                                    // user-visible symptom this surfacing fixes — the
                                    // recreate-on-flip race where the previous
                                    // session's camera hold has not finished releasing
                                    // when the new session tries to claim it). The old
                                    // code absorbed every such failure with no log and
                                    // no UI signal, so the user saw a black PreviewView
                                    // with no indication of cause. Same silent-failure
                                    // class as the 2026-05-08 → 2026-05-11 dt-* sweep.
                                    // Log the exception class (no PII per
                                    // logging-rules.md) and surface a failure overlay
                                    // so the next bind attempt has a starting point.
                                    Log.e(
                                        "Vauchi",
                                        "[QrCamera] bindToLifecycle failed (" +
                                            (if (useFrontCamera) "front" else "back") +
                                            "): ${e.javaClass.simpleName}",
                                    )
                                    bindFailure.value = e.javaClass.simpleName
                                }
                            },
                            androidx.core.content.ContextCompat
                                .getMainExecutor(ctx),
                        )

                        previewView
                    },
                )
                bindFailure.value?.let { exceptionClass ->
                    Text(
                        text =
                            localizationManager.t("exchange.camera_unavailable") +
                                "\n" + localizationManager.t("exchange.camera_start_failed") +
                                " ($exceptionClass)",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Shown in place of the camera preview when the CAMERA permission has not
 * been granted. Replaces the previous silent black box with an explicit
 * rationale + grant affordance; the system permission dialog itself is
 * driven by [rememberPermissionState].
 */
@Composable
private fun CameraPermissionPrompt(
    title: String,
    rationale: String,
    grantLabel: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrant) {
                Text(grantLabel)
            }
        }
    }
}
