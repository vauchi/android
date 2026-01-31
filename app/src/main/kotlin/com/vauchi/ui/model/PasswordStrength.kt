// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.model

enum class PasswordStrengthLevel {
    TooWeak,
    Fair,
    Strong,
    VeryStrong
}

data class PasswordStrengthResult(
    val level: PasswordStrengthLevel = PasswordStrengthLevel.TooWeak,
    val description: String = "",
    val feedback: String = "",
    val isAcceptable: Boolean = false
)
