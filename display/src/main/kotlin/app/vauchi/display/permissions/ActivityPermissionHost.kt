// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.UUID

class ActivityPermissionHost(
    private val activity: () -> ComponentActivity?,
) : PermissionRequestHost {
    private var active: Request? = null
    private val main = Handler(Looper.getMainLooper())

    override fun request(
        permissions: List<String>,
        onFinished: () -> Unit,
        onUnavailable: () -> Unit,
    ): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Permission host requires main thread" }
        val owner = activity()
        if (active != null || owner == null || owner.isFinishing || owner.isDestroyed ||
            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            onUnavailable()
            return AutoCloseable {}
        }
        val request = Request(owner, onFinished, onUnavailable)
        active = request
        try {
            owner.lifecycle.addObserver(request)
            // A canceled request must not deliver its late OS result to a replacement (#773).
            request.launcher =
                owner.activityResultRegistry.register(
                    "vauchi.display.permission.${UUID.randomUUID()}",
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { request.finished() }
            request.launcher!!.launch(permissions.toTypedArray())
        } catch (error: Exception) {
            request.abort()
            throw error
        }
        return request
    }

    private inner class Request(
        private var owner: ComponentActivity?,
        private var onFinished: (() -> Unit)?,
        private var onUnavailable: (() -> Unit)?,
    ) : AutoCloseable,
        LifecycleEventObserver {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        private var disposed = false

        fun finished() {
            if (disposed) return
            val notify = onFinished
            dispose(afterDispatch = true)
            notify?.invoke()
        }

        override fun onStateChanged(
            source: LifecycleOwner,
            event: Lifecycle.Event,
        ) {
            if (event != Lifecycle.Event.ON_DESTROY || disposed) return
            val notify = onUnavailable
            dispose(afterDispatch = false)
            notify?.invoke()
        }

        override fun close() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Permission host requires main thread" }
            // Android cannot dismiss this OS dialog; reserve it until reply/destruction (#773).
            onFinished = null
            onUnavailable = null
        }

        fun abort() = dispose(afterDispatch = false)

        private fun dispose(afterDispatch: Boolean) {
            if (disposed) return
            disposed = true
            val activity = owner
            val callback = launcher
            owner = null
            launcher = null
            onFinished = null
            onUnavailable = null
            val retire = {
                try {
                    callback?.unregister()
                } finally {
                    if (active === this) active = null
                }
            }
            try {
                activity?.lifecycle?.removeObserver(this)
            } finally {
                // AndroidX retires its launched key after callback return (#773).
                if (afterDispatch) main.post { retire() } else retire()
            }
        }
    }
}
