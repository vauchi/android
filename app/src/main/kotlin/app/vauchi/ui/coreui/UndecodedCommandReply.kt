// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import app.vauchi.ui.presentation.PresentationProtocol
import uniffi.vauchi_platform.MobileEvent

/**
 * What the exchange command handler answers for a command it cannot decode.
 * An unanswered hardware command would leave the engine waiting forever
 * (2026-06-11-silent-failure-mode-umbrella), so it gets HardwareUnavailable.
 * Surface commands also arrive here from Core's wakeup tick, which fires
 * presentation-invalidated as well — the surface reload already covers them,
 * and calling them "hardware unavailable" misled both the engine and the
 * user (vauchi/private#9).
 */
internal fun undecodedCommandReply(variant: String): MobileEvent? =
    if (variant in PresentationProtocol.SURFACE_COMMAND_VARIANTS) {
        null
    } else {
        MobileEvent.HardwareUnavailable(variant)
    }
