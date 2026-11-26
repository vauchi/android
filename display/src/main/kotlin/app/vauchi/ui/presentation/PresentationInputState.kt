// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PresentationInputState(
    initialValue: String,
) {
    var value by mutableStateOf(initialValue)
        private set

    fun accept(
        candidate: String,
        maxLength: Int?,
    ): String {
        val accepted = candidate.take(maxLength ?: Int.MAX_VALUE)
        value = accepted
        return accepted
    }
}
