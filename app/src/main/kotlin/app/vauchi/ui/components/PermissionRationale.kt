// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vauchi.util.LocalizationManager

/**
 * Manages a single permission request with rationale dialog.
 *
 * Shows an explanatory dialog before requesting the permission when Android
 * indicates the user previously denied it (shouldShowRequestPermissionRationale).
 * On first request, the system dialog is shown directly.
 *
 * Usage:
 * ```
 * val cameraState = rememberPermissionState(
 *     permission = Manifest.permission.CAMERA,
 *     title = "Camera Required",
 *     rationale = "Vauchi needs the camera to scan QR codes during contact exchange.",
 * )
 * LaunchedEffect(Unit) { cameraState.request() }
 * if (cameraState.isGranted) { /* show camera */ }
 * ```
 */
class PermissionState(
    val isGranted: Boolean,
    val showRationale: Boolean,
    private val onRequest: () -> Unit,
    private val onDismissRationale: () -> Unit,
    val rationaleTitle: String,
    val rationaleText: String,
) {
    fun request() = onRequest()

    fun dismissRationale() = onDismissRationale()
}

@Composable
fun rememberPermissionState(
    permission: String,
    title: String,
    rationale: String,
    // Invoked once when the OS permission prompt resolves with a DENIAL (the
    // launcher result callback fires `granted == false`). Opt-in (default no-op)
    // so existing callers are unchanged. This is the only point a *definitive*
    // negative decision exists — firing here can never race the pending prompt
    // (the callback fires only after the modal dismisses with a result). See
    // T0.3 design 2026-06-14-t03-camera-deny-forwarding-design.md §1.
    onDenied: (() -> Unit)? = null,
): PermissionState {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val activity = context as? Activity

    var isGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var showRationale by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            isGranted = granted
            // Fire the denial hook only on a real negative decision — never on
            // grant (the grant asymmetry: grants are re-learned via the in-app
            // affordance, not an event). T0.3 §1. Rule extracted to
            // [permissionResultIsDenial] so it is unit-testable without the OS
            // prompt (CC-23).
            if (permissionResultIsDenial(granted)) onDenied?.invoke()
        }

    // Re-check on resume so a grant made via the system dialog or the OS
    // Settings screen is reflected even when the launcher callback does not
    // re-fire. Fixes the scanner staying on the permission prompt after the
    // user grants. See _private/docs/problems/2026-06-06-exchange-ritual-flow/.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permission) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isGranted =
                        ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onRequest: () -> Unit = {
        if (isGranted) {
            // Already granted
        } else if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            showRationale = true
        } else {
            launcher.launch(permission)
        }
    }

    val onDismissRationale: () -> Unit = {
        showRationale = false
        launcher.launch(permission)
    }

    return remember(isGranted, showRationale) {
        PermissionState(
            isGranted = isGranted,
            showRationale = showRationale,
            onRequest = onRequest,
            onDismissRationale = onDismissRationale,
            rationaleTitle = title,
            rationaleText = rationale,
        )
    }
}

/**
 * Composable to manage multiple permissions with rationale dialogs.
 *
 * Usage:
 * ```
 * val bleState = rememberMultiplePermissionsState(
 *     permissions = blePermissions,
 *     title = "Bluetooth Required",
 *     rationale = "Vauchi uses Bluetooth to exchange contact cards nearby.",
 * )
 * LaunchedEffect(Unit) { bleState.request() }
 * ```
 */
class MultiplePermissionsState(
    val allGranted: Boolean,
    val showRationale: Boolean,
    private val onRequest: () -> Unit,
    private val onDismissRationale: () -> Unit,
    val rationaleTitle: String,
    val rationaleText: String,
) {
    fun request() = onRequest()

    fun dismissRationale() = onDismissRationale()
}

@Composable
fun rememberMultiplePermissionsState(
    permissions: List<String>,
    title: String,
    rationale: String,
): MultiplePermissionsState {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    val activity = context as? Activity

    var allGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var showRationale by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            allGranted = results.values.all { it }
        }

    // Re-check on resume (see single-permission variant above).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permissions) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    allGranted =
                        permissions.all {
                            ContextCompat.checkSelfPermission(context, it) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onRequest: () -> Unit = {
        if (allGranted) {
            // Already granted
        } else if (activity != null &&
            permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        ) {
            showRationale = true
        } else {
            launcher.launch(permissions.toTypedArray())
        }
    }

    val onDismissRationale: () -> Unit = {
        showRationale = false
        launcher.launch(permissions.toTypedArray())
    }

    return remember(allGranted, showRationale) {
        MultiplePermissionsState(
            allGranted = allGranted,
            showRationale = showRationale,
            onRequest = onRequest,
            onDismissRationale = onDismissRationale,
            rationaleTitle = title,
            rationaleText = rationale,
        )
    }
}

/**
 * Shows the rationale AlertDialog when [PermissionState.showRationale] is true.
 * Place this in your composable tree alongside permission-gated content.
 */
@Composable
fun PermissionRationaleDialog(state: PermissionState) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    if (state.showRationale) {
        AlertDialog(
            onDismissRequest = { state.dismissRationale() },
            title = { Text(state.rationaleTitle) },
            text = { Text(state.rationaleText) },
            confirmButton = {
                TextButton(onClick = { state.dismissRationale() }) {
                    Text(localizationManager.t("action.continue"))
                }
            },
        )
    }
}

/**
 * Shows the rationale AlertDialog for [MultiplePermissionsState].
 */
@Composable
fun PermissionRationaleDialog(state: MultiplePermissionsState) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    if (state.showRationale) {
        AlertDialog(
            onDismissRequest = { state.dismissRationale() },
            title = { Text(state.rationaleTitle) },
            text = { Text(state.rationaleText) },
            confirmButton = {
                TextButton(onClick = { state.dismissRationale() }) {
                    Text(localizationManager.t("action.continue"))
                }
            },
        )
    }
}

/**
 * Whether a permission-launcher result should be reported as a denial: true on
 * a negative decision, false on a grant. Pure + OS-free so the fire rule that
 * drives [rememberPermissionState]'s `onDenied` hook — notify on deny, NEVER on
 * grant (the grant asymmetry) — is unit-testable without the OS prompt (CC-23).
 */
internal fun permissionResultIsDenial(granted: Boolean): Boolean = !granted
