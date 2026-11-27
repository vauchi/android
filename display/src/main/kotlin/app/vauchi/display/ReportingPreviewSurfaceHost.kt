// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.view.SurfaceView
import app.vauchi.contract.Event

internal class ReportingPreviewSurfaceHost(
    private val delegate: PreviewSurfaceHost,
    private val owner: AndroidDisplayExecutor,
) : PreviewSurfaceHost {
    override fun attach(
        previewId: String,
        view: SurfaceView,
    ): AutoCloseable {
        val lease =
            try {
                owner.acquirePreview { delegate.attach(previewId, view) }
            } catch (_: Exception) {
                owner.report(Event.HardwareError("camera_preview_failed", "camera"))
                null
            }
        return if (lease == null) {
            AutoCloseable { }
        } else {
            AutoCloseable {
                try {
                    lease.close()
                } catch (_: Exception) {
                    owner.report(Event.HardwareError("camera_preview_cleanup_failed", "camera"))
                }
            }
        }
    }
}
