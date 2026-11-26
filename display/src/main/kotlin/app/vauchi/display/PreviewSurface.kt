// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView

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
) {
    val context = LocalContext.current
    val surface = remember(context) { SurfaceView(context) }
    AndroidView(
        factory = { surface },
        modifier =
            modifier.fillMaxSize().semantics {
                accessibilityLabel?.let { contentDescription = it }
            },
    )
    DisposableEffect(previewId, surface, host) {
        val attachment = host.attach(previewId, surface)
        onDispose { attachment.close() }
    }
}
