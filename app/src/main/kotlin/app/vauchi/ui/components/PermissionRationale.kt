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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
): PermissionState {
    val context = LocalContext.current
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
    if (state.showRationale) {
        AlertDialog(
            onDismissRequest = { state.dismissRationale() },
            title = { Text(state.rationaleTitle) },
            text = { Text(state.rationaleText) },
            confirmButton = {
                TextButton(onClick = { state.dismissRationale() }) {
                    Text("Continue")
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
    if (state.showRationale) {
        AlertDialog(
            onDismissRequest = { state.dismissRationale() },
            title = { Text(state.rationaleTitle) },
            text = { Text(state.rationaleText) },
            confirmButton = {
                TextButton(onClick = { state.dismissRationale() }) {
                    Text("Continue")
                }
            },
        )
    }
}
