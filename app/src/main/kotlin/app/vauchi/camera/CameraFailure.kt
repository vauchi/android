// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.camera

import uniffi.vauchi_platform.MobileEvent

/**
 * Maps a definitive Android camera-permission denial onto the hardware event
 * core expects, so the exchange ledger / `CameraGate` fails the QR leg visibly
 * (grant affordance) instead of leaving the scanner on a dead overlay while
 * core waits forever
 * (`2026-06-11-exchange-waits-forever-without-capabilities`, T0.3).
 *
 * Camera denial is reported through the generic presentation host's native
 * callback. Unlike BLE (a free-form error), it is a clean boolean from the
 * permission-launcher result, so this mapper takes no message.
 */
object CameraFailure {
    const val TRANSPORT = "camera"

    fun deniedEvent(): MobileEvent = MobileEvent.PermissionDenied(TRANSPORT)
}
