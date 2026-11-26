// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.view.SurfaceView

internal class ReportingPreviewSurfaceHost(
    private val delegate: PreviewSurfaceHost,
    private val reportFailure: (String) -> Unit,
) : PreviewSurfaceHost {
    override fun attach(
        previewId: String,
        view: SurfaceView,
    ): AutoCloseable =
        try {
            val lease = delegate.attach(previewId, view)
            AutoCloseable {
                try {
                    lease.close()
                } catch (_: Exception) {
                    reportFailure("camera_preview_cleanup_failed")
                }
            }
        } catch (_: Exception) {
            reportFailure("camera_preview_failed")
            AutoCloseable { }
        }
}
