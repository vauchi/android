// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Native media attachment for the renderer; owns no Core or camera implementation. */
interface PresentationMedia {
    @Composable
    fun Scanner(
        accessibilityLabel: String?,
        onScanned: (String) -> Unit,
        onPermissionDenied: () -> Unit,
    )

    @Composable
    fun Code(
        data: String,
        accessibilityLabel: String?,
        placement: QrPlacement?,
        errorCorrection: String?,
        modifier: Modifier,
    )
}

internal val LocalPresentationMedia = compositionLocalOf<PresentationMedia?> { null }
