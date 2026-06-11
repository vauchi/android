// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * Fulfils core's `Command::FilePickFromUser` with the system document
 * picker. Must be hosted by every screen tree that dispatches actions
 * through [CoreAppViewModel] — including pre-identity onboarding,
 * where `restore_backup` emits the command
 * (`2026-06-11-android-restore-paths-all-dead`).
 *
 * Pick → [CoreAppViewModel.handleFilePicked] (bytes + display name);
 * dismiss or unreadable content → [CoreAppViewModel.handleFilePickCancelled].
 */
@Composable
fun FilePickHandler(viewModel: CoreAppViewModel) {
    val context = LocalContext.current
    val filePickRequest by viewModel.filePickRequest.collectAsState()

    val documentPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                try {
                    val bytes =
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        viewModel.handleFilePicked(bytes, uri.displayName(context))
                    } else {
                        viewModel.handleFilePickCancelled()
                    }
                } catch (_: Exception) {
                    viewModel.handleFilePickCancelled()
                }
            } else {
                viewModel.handleFilePickCancelled()
            }
        }

    LaunchedEffect(filePickRequest) {
        val request = filePickRequest ?: return@LaunchedEffect
        viewModel.consumeFilePickRequest()
        // OpenDocument with an empty filter shows nothing on some
        // OEM pickers — fall back to all documents.
        val mimeTypes = request.mimeTypes.ifEmpty { listOf("*/*") }
        documentPickerLauncher.launch(mimeTypes.toTypedArray())
    }
}

private fun Uri.displayName(context: Context): String =
    context.contentResolver
        .query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        ?: lastPathSegment
        ?: "file"
