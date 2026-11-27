// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import app.vauchi.contract.Orientation

/**
 * Call on Android's main thread, serialized with display execution and close.
 * The provider may change after recreation; old overrides retire before new ones.
 * Participating hosts coordinate native window ownership, not legacy app writes.
 */
class ActivityWindowHost(
    private val activity: () -> ComponentActivity?,
) : WindowControlHost {
    private var closed = false
    private var active: NativeWindowLease? = null

    override fun setBrightness(level: Float?) {
        if (level?.isFinite() == false) throw WindowHostUnavailable()
        update { it.setBrightness(level) }
    }

    override fun setIdleTimerDisabled(disabled: Boolean) = update { it.setIdleTimerDisabled(disabled) }

    override fun setOrientation(orientation: Orientation?) = update { it.setOrientation(orientation) }

    private fun update(change: (NativeWindowLease) -> Unit) {
        requireMain()
        if (closed) throw WindowHostUnavailable()
        try {
            val owner = activity() ?: throw WindowHostUnavailable()
            if (closed || owner.isDestroyed || owner.isFinishing || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                throw WindowHostUnavailable()
            }
            active?.let { if (!it.belongsTo(owner)) retire(it) }
            val lease =
                active ?: NativeWindowLease.acquire(owner, ::retire).also {
                    active = it
                    it.observe()
                }
            change(lease)
            if (lease.disposed) throw WindowHostUnavailable()
            if (!lease.hasOverrides) retire(lease)
        } catch (_: Exception) {
            active?.let { runCatching { retire(it) } }
            throw WindowHostUnavailable()
        }
    }

    private fun retire(lease: NativeWindowLease) {
        if (active === lease) active = null
        lease.close()
    }

    override fun close() {
        if (closed) return
        requireMain()
        closed = true
        active?.let(::retire)
    }

    private fun requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw WindowHostUnavailable()
    }
}
