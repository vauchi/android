// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object AppPresentationMedia : PresentationMedia {
    @Composable
    override fun Scanner(
        accessibilityLabel: String?,
        onScanned: (String) -> Unit,
        onPermissionDenied: () -> Unit,
    ) {
        QrScanner(accessibilityLabel, onScanned, onPermissionDenied)
    }

    @Composable
    override fun Code(
        data: String,
        accessibilityLabel: String?,
        placement: QrPlacement?,
        errorCorrection: String?,
        modifier: Modifier,
    ) {
        QrDisplay(
            data = data,
            accessibilityLabel = accessibilityLabel,
            placement = placement,
            errorCorrection = errorCorrection,
            modifier = modifier,
        )
    }
}
