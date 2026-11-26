// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome

internal class AndroidFilePickerPort(
    private val host: ActivityFilePickerHost?,
    private val onEvent: (Event) -> Unit,
) : AutoCloseable {
    private var active: Request? = null
    private var closed = false

    private class Request(
        val id: String,
    ) {
        var lease: AutoCloseable? = null
    }

    fun publishCapabilities() {
        if (!closed) onEvent(Event.FilePickerCapabilitiesChanged(host != null))
    }

    fun execute(command: Command): Boolean {
        if (closed) return false
        when (command) {
            is Command.PickFile -> {
                pick(command)
            }

            is Command.CancelFilePick -> {
                active?.takeIf { it.id == command.requestId }?.let { request ->
                    active = null
                    request.lease?.close()
                    request.lease = null
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun pick(command: Command.PickFile) {
        val id = command.requestId
        if (id.isEmpty() || id.length > 128 || id.toByteArray(Charsets.UTF_8).size > 128) {
            onEvent(Event.HardwareError("file_pick_invalid_request", "file_picker"))
            return
        }
        val pending = active
        if (pending != null) {
            if (pending.id != id) onEvent(Event.FilePickCompleted(FilePickOutcome.Failed(FilePickFailure.InvalidRequest), id))
            return
        }
        val request = Request(id)
        active = request
        val native = host
        if (native == null) {
            finish(request, FilePickOutcome.Failed(FilePickFailure.ReadFailed))
            return
        }
        try {
            val lease = native.pick(command) { finish(request, it) }
            if (!closed && active === request) request.lease = lease else lease.close()
        } catch (_: Exception) {
            finish(request, FilePickOutcome.Failed(FilePickFailure.ReadFailed))
        }
    }

    private fun finish(
        request: Request,
        outcome: FilePickOutcome,
    ) {
        if (closed || active !== request) return
        active = null
        request.lease?.close()
        request.lease = null
        onEvent(Event.FilePickCompleted(outcome, request.id))
    }

    override fun close() {
        if (closed) return
        closed = true
        val pending = active
        active = null
        try {
            pending?.lease?.close()
        } finally {
            pending?.lease = null
            host?.close()
        }
    }
}
