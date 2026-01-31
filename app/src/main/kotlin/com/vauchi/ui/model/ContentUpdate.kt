// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.model

enum class ContentUpdateType {
    Networks,
    Locales,
    Themes,
    Help
}

sealed class ContentUpdateStatus {
    data object UpToDate : ContentUpdateStatus()
    data class UpdatesAvailable(val types: List<ContentUpdateType>) : ContentUpdateStatus()
    data class CheckFailed(val error: String) : ContentUpdateStatus()
    data object Disabled : ContentUpdateStatus()
}

sealed class ContentApplyResult {
    data object NoUpdates : ContentApplyResult()
    data class Applied(val applied: List<ContentUpdateType>, val failed: List<ContentUpdateType>) : ContentApplyResult()
    data object Disabled : ContentApplyResult()
    data class Error(val error: String) : ContentApplyResult()
}
