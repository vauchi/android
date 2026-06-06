// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

/**
 * Activity-bound BLE work items derived from core's BLE `Command`s.
 *
 * BLE needs a `Context` + lifecycle, so [app.vauchi.ui.coreui.CoreAppViewModel]
 * forwards these over a buffered `SharedFlow` (not a latest-only `StateFlow` —
 * a handshake emits many commands in quick succession and none may be dropped),
 * and the Activity dispatches them to [BleCentral] / [BlePeripheral].
 *
 * Slice S1 covers discovery only (scan + advertise); Connect / Write / Read /
 * Disconnect arrive in S2/S3 — see
 * `_private/docs/problems/2026-06-06-android-ble-execution/`.
 */
sealed interface BleCommand {
    /** `Command::BleStartScanning` — central scans for [serviceUuid]. */
    data class StartScan(
        val serviceUuid: String,
    ) : BleCommand

    /** `Command::BleStartAdvertising` — peripheral advertises [serviceUuid]. */
    data class StartAdvertise(
        val serviceUuid: String,
        val payload: ByteArray,
    ) : BleCommand

    /** `Command::BleConnect` — central connects to a discovered peripheral. */
    data class Connect(
        val deviceId: String,
    ) : BleCommand

    /** `Command::BleDisconnect` — tear down the current connection. */
    data object Disconnect : BleCommand

    /**
     * `Command::BleWriteCharacteristic` — send [data] on [uuid]. Routed by the
     * UUID: a responder-notify characteristic ([BleUuids.peripheralNotifyChars])
     * is a peripheral notify; otherwise a central GATT write.
     */
    data class Write(
        val uuid: String,
        val data: ByteArray,
    ) : BleCommand

    /** `Command::BleReadCharacteristic` — central reads [uuid]. */
    data class Read(
        val uuid: String,
    ) : BleCommand
}
