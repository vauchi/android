// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.intents

import app.vauchi.contract.Command
import app.vauchi.contract.Event

internal class AndroidExternalIntentPort(
    private val host: ActivityExternalIntentHost?,
    private val onEvent: (Event) -> Unit,
) : AutoCloseable {
    @Volatile private var closed = false

    fun execute(command: Command): Boolean {
        if (closed) return false
        return when (command) {
            is Command.OpenExternalUrl -> perform("external_url") { it.open(command.url) }
            is Command.ShowShareSheet -> perform("share_sheet") { it.share(command.url) }
            else -> false
        }
    }

    private fun perform(
        transport: String,
        launch: (ActivityExternalIntentHost) -> Unit,
    ): Boolean {
        val native = host
        if (native == null) {
            onEvent(Event.HardwareUnavailable(transport))
        } else {
            try {
                launch(native)
            } catch (_: Exception) {
                onEvent(Event.HardwareUnavailable(transport))
            }
        }
        return true
    }

    override fun close() {
        closed = true
        host?.close()
    }
}
