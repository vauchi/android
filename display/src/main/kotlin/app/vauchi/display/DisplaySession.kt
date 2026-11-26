// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.Command
import app.vauchi.contract.Event
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
    private val mutableState = MutableStateFlow(DisplaySnapshot())
    val state: StateFlow<DisplaySnapshot> = mutableState.asStateFlow()

    fun apply(commands: List<Command>): Set<Int> = emptySet()

    fun consumeEffect(token: ULong) = Unit

    fun report(event: Event) = Unit
}
