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
 * Only this *mapper object* mirrors [app.vauchi.ble.BleFailure]. The
 * forwarding path differs: BLE reports via a direct view-model callback
 * (`onBleOperationFailed`) wired in MainActivity, whereas the camera denial is
 * detected inside a composable, so it is reported as a sentinel
 * [DENIED_ACTION_ID] `ActionPressed` intercepted in `CoreAppViewModel.handleAction`
 * (the single chokepoint every render path funnels through). Unlike BLE (a
 * free-form `String?` error), a camera denial is a clean boolean from the
 * permission-launcher result callback, so the mapper takes no message — the
 * *fire condition* (only on a post-decision denial) lives at the call site in
 * `rememberPermissionState`.
 */
object CameraFailure {
    const val TRANSPORT = "camera"

    /**
     * Sentinel `UserAction.ActionPressed` id emitted by the QR scanner on a
     * camera-permission denial and intercepted by `CoreScreenView` (which holds
     * the view model) to call `onCameraPermissionDenied()`. Routed as an
     * `ActionPressed` rather than a new `UserAction` variant so the custom
     * `UserActionSerializer` is untouched; the action is intercepted before it
     * reaches core and is never serialized.
     */
    const val DENIED_ACTION_ID = "__camera_permission_denied"

    fun deniedEvent(): MobileEvent = MobileEvent.PermissionDenied(TRANSPORT)
}
