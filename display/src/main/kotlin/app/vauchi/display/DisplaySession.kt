// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.Command
import app.vauchi.contract.ContractJson
import app.vauchi.contract.Event
import app.vauchi.ui.presentation.PresentationProtocol
import app.vauchi.ui.presentation.PresentationReducer
import app.vauchi.ui.presentation.PresentationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayEffect(
    val token: ULong,
    val command: Command,
) {
    override fun toString(): String = "DisplayEffect"
}

data class DisplaySnapshot(
    val presentation: PresentationState = PresentationState(),
    val previewId: String? = null,
    val effects: List<DisplayEffect> = emptyList(),
) {
    override fun toString(): String = "DisplaySnapshot"
}

class DisplaySession(
    private val onEvent: (Event) -> Unit,
) {
    private val monitor = Any()
    private var nextToken = 1uL
    private val mutableState = MutableStateFlow(DisplaySnapshot())
    val state: StateFlow<DisplaySnapshot> = mutableState.asStateFlow()

    fun apply(commands: List<Command>): Set<Int> =
        synchronized(monitor) {
            var pending = mutableState.value
            var token = nextToken
            val handled = mutableSetOf<Int>()
            commands.forEachIndexed { index, command ->
                pending =
                    when (command) {
                        is Command.ReplaceSurface,
                        is Command.SetContextBar,
                        is Command.SetNavigation,
                        is Command.PresentOverlay,
                        is Command.DismissOverlay,
                        is Command.SetPresentationProfile,
                        -> {
                            val wire = ContractJson.encodeCommand(command)
                            val envelope = PresentationProtocol.decodeEnvelope("{\"commands\":[$wire]}")
                            pending.copy(presentation = PresentationReducer.apply(pending.presentation, envelope.commands).state)
                        }

                        is Command.CameraViewport -> {
                            pending.copy(previewId = command.previewId)
                        }

                        is Command.CameraStop -> {
                            pending.copy(previewId = pending.previewId.takeUnless { it == command.previewId })
                        }

                        is Command.ShowToast,
                        is Command.PresentAlert,
                        is Command.Celebrate,
                        -> {
                            check(token != ULong.MAX_VALUE) { "display effect tokens exhausted" }
                            pending.copy(effects = pending.effects + DisplayEffect(token++, command))
                        }

                        else -> {
                            return@forEachIndexed
                        }
                    }
                handled.add(index)
            }
            nextToken = token
            mutableState.value = pending
            handled
        }

    fun consumeEffect(token: ULong) {
        synchronized(monitor) {
            val current = mutableState.value
            mutableState.value = current.copy(effects = current.effects.filterNot { it.token == token })
        }
    }

    fun report(event: Event) = onEvent(event)
}
