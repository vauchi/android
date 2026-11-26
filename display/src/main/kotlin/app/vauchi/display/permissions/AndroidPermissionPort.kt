// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.vauchi.contract.Command
import app.vauchi.contract.Event

class AndroidPermissionPort(
    context: Context,
    private val host: PermissionRequestHost?,
    private val onEvent: (Event) -> Unit,
) : AutoCloseable {
    private val application = context.applicationContext
    private val guard = Any()
    private var closed = false
    private var active: Request? = null

    private class Request(
        val transport: String,
        val permissions: List<String>,
    ) {
        var lease: AutoCloseable? = null
    }

    fun execute(command: Command): Boolean {
        if (synchronized(guard) { closed } || command !is Command.RequestPermission) return false
        val transport = command.transport
        val permissions = permissionsFor(transport)
        if (permissions == null) {
            onEvent(Event.HardwareUnavailable(transport))
            return true
        }
        val granted =
            try {
                runtimeGranted(transport, permissions)
            } catch (_: Exception) {
                onEvent(Event.HardwareError("permission_request_failed", transport))
                return true
            }
        if (granted) {
            onEvent(observed(transport, permissions))
            return true
        }
        val nativeHost = host
        if (nativeHost == null) {
            onEvent(Event.HardwareUnavailable(transport))
            return true
        }
        val request = Request(transport, permissions)
        val admitted =
            synchronized(guard) {
                if (closed) return false
                if (active == null) {
                    active = request
                    true
                } else {
                    false
                }
            }
        if (!admitted) {
            onEvent(Event.HardwareError("permission_request_busy", transport))
            return true
        }
        try {
            val lease =
                nativeHost.request(
                    permissions.toList(),
                    { finish(request, observed(transport, permissions)) },
                    { finish(request, Event.HardwareUnavailable(transport)) },
                )
            val keep =
                synchronized(guard) {
                    if (!closed && active === request) {
                        request.lease = lease
                        true
                    } else {
                        false
                    }
                }
            if (!keep) lease.close()
        } catch (_: Exception) {
            finish(request, Event.HardwareError("permission_request_failed", transport))
        }
        return true
    }

    private fun finish(
        request: Request,
        event: Event,
    ) {
        val lease =
            synchronized(guard) {
                if (closed || active !== request) return
                active = null
                request.lease.also { request.lease = null }
            }
        try {
            lease?.close()
        } finally {
            onEvent(event)
        }
    }

    private fun runtimeGranted(
        transport: String,
        permissions: List<String>,
    ): Boolean {
        val granted = { name: String -> ContextCompat.checkSelfPermission(application, name) == PackageManager.PERMISSION_GRANTED }
        return if (transport == "location") permissions.any(granted) else permissions.all(granted)
    }

    private fun observed(
        transport: String,
        permissions: List<String>,
    ): Event =
        try {
            val enabled =
                transport != "notifications" ||
                    (application.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.areNotificationsEnabled() == true
            if (enabled && runtimeGranted(transport, permissions)) {
                Event.PermissionGranted(transport)
            } else {
                Event.PermissionDenied(transport)
            }
        } catch (_: Exception) {
            Event.HardwareError("permission_request_failed", transport)
        }

    private fun permissionsFor(transport: String): List<String>? =
        when (transport) {
            "camera" -> {
                listOf(Manifest.permission.CAMERA)
            }

            "microphone" -> {
                listOf(Manifest.permission.RECORD_AUDIO)
            }

            "location" -> {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            "ble" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                } else {
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            "notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            }

            else -> {
                null
            }
        }

    override fun close() {
        val lease =
            synchronized(guard) {
                if (closed) return
                closed = true
                active?.lease.also { active = null }
            }
        lease?.close()
    }
}
