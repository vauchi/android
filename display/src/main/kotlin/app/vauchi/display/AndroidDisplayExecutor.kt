// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.content.Context
import android.os.Looper
import androidx.core.content.ContextCompat
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.display.files.ActivityFilePickerHost
import app.vauchi.display.files.AndroidFilePickerPort
import app.vauchi.display.permissions.AndroidPermissionPort
import app.vauchi.display.permissions.PermissionRequestHost
import app.vauchi.display.window.AndroidWindowPort
import app.vauchi.display.window.WindowControlHost
import app.vauchi.ui.presentation.RevisionedOverlay

/**
 * [onEvent] admits typed events to the host-owned bounded inbox; it must not
 * dispatch Core. The host serializes UI execution, callbacks and closure.
 */
class AndroidDisplayExecutor(
    context: Context,
    permissionHost: PermissionRequestHost? = null,
    filePickerHost: ActivityFilePickerHost? = null,
    windowHost: WindowControlHost? = null,
    private val onEvent: (Event) -> Boolean,
) : AutoCloseable {
    private val guard = Any()
    private val main = ContextCompat.getMainExecutor(context.applicationContext)
    private var closed = false
    private val permissions = AndroidPermissionPort(context, permissionHost, ::report)
    private val files = AndroidFilePickerPort(filePickerHost, ::report)
    private val windows = AndroidWindowPort(windowHost, ::report)
    private val session = DisplaySession { report(it) }
    val state = session.state

    fun execute(command: Command): Boolean =
        synchronized(guard) {
            if (closed) return false
            permissions.execute(command) || files.execute(command) || windows.execute(command) ||
                session.apply(listOf(command)).contains(0)
        }

    fun publishCapabilities() {
        synchronized(guard) {
            if (!closed) files.publishCapabilities()
        }
    }

    fun consumeEffect(token: ULong) {
        synchronized(guard) {
            if (!closed) session.consumeEffect(token)
        }
    }

    internal fun dismissOverlay(expected: RevisionedOverlay): Boolean =
        synchronized(guard) {
            if (closed) return false
            session.dismissOverlay(expected)?.let(::report) ?: false
        }

    internal fun acquirePreview(acquire: () -> AutoCloseable): AutoCloseable? =
        synchronized(guard) {
            if (closed) return null
            val lease = acquire()
            if (closed) {
                lease.close()
                null
            } else {
                lease
            }
        }

    fun report(event: Event): Boolean {
        val accepted =
            synchronized(guard) {
                if (closed) return false
                try {
                    onEvent(event)
                } catch (_: Exception) {
                    false
                }
            }
        if (!accepted) close()
        return accepted
    }

    override fun close() {
        val first =
            synchronized(guard) {
                if (closed) {
                    false
                } else {
                    closed = true
                    session.release()
                    true
                }
            }
        // Retire the previous window before a same-frame display handoff (#773).
        if (Looper.myLooper() == Looper.getMainLooper()) windows.close()
        if (!first) return
        main.execute {
            for (resource in listOf(permissions, files, windows)) {
                try {
                    resource.close()
                } catch (_: Exception) {
                    // Closed owners cannot publish cleanup failures (#773).
                }
            }
        }
    }
}
