// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import android.content.pm.ActivityInfo
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.vauchi.contract.Orientation
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class NativeWindowLease private constructor(
    activity: ComponentActivity,
    private val window: Window,
    private val token: Any,
    private val onRetired: (NativeWindowLease) -> Unit,
) : AutoCloseable,
    LifecycleEventObserver {
    private val activity = WeakReference(activity)
    private var brightness: Float? = null
    private var idle: Boolean? = null
    private var orientation: Int? = null
    var disposed = false
        private set
    val hasOverrides: Boolean get() = brightness != null || idle != null || orientation != null

    fun belongsTo(owner: ComponentActivity): Boolean = activity.get() === owner && window === owner.window

    fun observe() {
        val owner = activity.get() ?: throw WindowHostUnavailable()
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) throw WindowHostUnavailable()
        owner.lifecycle.addObserver(this)
    }

    fun setBrightness(level: Float?) {
        if (level == null) {
            writeBrightness(brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            brightness = null
        } else {
            if (brightness == null) brightness = window.attributes.screenBrightness
            writeBrightness(level.coerceIn(0f, 1f))
        }
    }

    fun setIdleTimerDisabled(disabled: Boolean) {
        if (disabled) {
            if (idle == null) idle = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            writeIdle(true)
        } else {
            writeIdle(false)
            idle = null
        }
    }

    fun setOrientation(value: Orientation?) {
        val owner = activity.get() ?: throw WindowHostUnavailable()
        if (value == null) {
            owner.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientation = null
        } else {
            if (orientation == null) orientation = owner.requestedOrientation
            owner.requestedOrientation =
                when (value) {
                    Orientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    Orientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
        }
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        if (event == Lifecycle.Event.ON_DESTROY && !disposed) {
            // The disappearing Activity cannot consume restoration errors (#773).
            runCatching { onRetired(this) }
        }
    }

    override fun close() {
        if (disposed) return
        disposed = true
        val priorBrightness = brightness
        val priorIdle = idle
        val priorOrientation = orientation
        brightness = null
        idle = null
        orientation = null
        var failed = false

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (_: Exception) {
                failed = true
            }
        }
        try {
            priorBrightness?.let { attempt { writeBrightness(it) } }
            priorIdle?.let { attempt { writeIdle(it) } }
            priorOrientation?.let { attempt { (activity.get() ?: throw WindowHostUnavailable()).requestedOrientation = it } }
            activity.get()?.let { owner -> attempt { owner.lifecycle.removeObserver(this) } }
        } finally {
            synchronized(owners) { if (owners[window] === token) owners.remove(window) }
        }
        if (failed) throw WindowHostUnavailable()
    }

    private fun writeBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level }
    }

    private fun writeIdle(disabled: Boolean) {
        if (disabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        // Values never retain the weak Window keys; one token per native window (#773).
        private val owners = WeakHashMap<Window, Any>()

        fun acquire(
            activity: ComponentActivity,
            onRetired: (NativeWindowLease) -> Unit,
        ): NativeWindowLease {
            val window = activity.window ?: throw WindowHostUnavailable()
            val token = Any()
            synchronized(owners) {
                if (owners.containsKey(window)) throw WindowHostUnavailable()
                owners[window] = token
            }
            return NativeWindowLease(activity, window, token, onRetired)
        }
    }
}
