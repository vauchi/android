// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

fun interface PreviewSurfaceHost {
    fun attach(
        previewId: String,
        view: SurfaceView,
    ): AutoCloseable
}

@Composable
fun PreviewSurface(
    previewId: String,
    accessibilityLabel: String?,
    host: PreviewSurfaceHost,
    modifier: Modifier = Modifier,
) = Unit
