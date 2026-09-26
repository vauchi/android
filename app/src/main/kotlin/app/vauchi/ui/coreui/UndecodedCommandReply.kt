// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import uniffi.vauchi_platform.MobileEvent

/**
 * What the exchange command handler answers for a command it cannot decode.
 * An unanswered hardware command would leave the engine waiting forever
 * (2026-06-11-silent-failure-mode-umbrella), so it gets HardwareUnavailable.
 */
internal fun undecodedCommandReply(variant: String): MobileEvent? = MobileEvent.HardwareUnavailable(variant)
