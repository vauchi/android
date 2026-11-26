// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.vauchi.ui.presentation.PresentationMedia

internal class NativePreviewMedia(
    private val previewId: String?,
    private val host: PreviewSurfaceHost,
    codeHost: PresentationMedia,
) : PresentationMedia by codeHost {
    @Composable
    override fun Scanner(
        accessibilityLabel: String?,
        onScanned: (String) -> Unit,
        onPermissionDenied: () -> Unit,
    ) {
        // Capture and its events belong to hardware, not the renderer's legacy callbacks (private#1358).
        Surface(
            modifier =
                Modifier
                    .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            previewId?.let { PreviewSurface(it, accessibilityLabel, host) }
        }
    }
}
