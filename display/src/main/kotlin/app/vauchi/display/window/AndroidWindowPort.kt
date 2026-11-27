// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import app.vauchi.contract.Command
import app.vauchi.contract.Event

class AndroidWindowPort(
    private val host: WindowControlHost?,
    private val onEvent: (Event) -> Unit,
) : AutoCloseable {
    private var closed = false

    fun execute(command: Command): Boolean {
        if (closed) return false
        return when (command) {
            is Command.SetScreenBrightness -> {
                if (command.level?.isFinite() == false) {
                    onEvent(Event.HardwareError("window_invalid_brightness", "screen_brightness"))
                    true
                } else {
                    perform("screen_brightness") { it.setBrightness(command.level) }
                }
            }

            is Command.SetIdleTimerDisabled -> {
                perform("idle_timer") { it.setIdleTimerDisabled(command.disabled) }
            }

            is Command.SetOrientationLock -> {
                perform("orientation_lock") { it.setOrientation(command.orientation) }
            }

            else -> {
                false
            }
        }
    }

    private fun perform(
        transport: String,
        update: (WindowControlHost) -> Unit,
    ): Boolean {
        val native = host
        if (native == null) {
            onEvent(Event.HardwareUnavailable(transport))
        } else {
            try {
                update(native)
            } catch (_: Exception) {
                onEvent(Event.HardwareUnavailable(transport))
            }
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            host?.close()
        } catch (_: Exception) {
            onEvent(Event.HardwareError("window_restore_failed", "window"))
        }
    }
}
