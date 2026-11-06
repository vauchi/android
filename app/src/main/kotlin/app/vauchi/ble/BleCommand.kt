// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import uniffi.vauchi_platform.MobileBleLinkDirection

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

    /** `Command::BleStopScanning` — central stops scanning. */
    data object StopScanning : BleCommand

    /** `Command::BleStopAdvertising` — peripheral withdraws and closes its GATT server. */
    data object StopAdvertising : BleCommand

    /** `Command::BleConnect` — central connects to a discovered peripheral. */
    data class Connect(
        val deviceId: String,
    ) : BleCommand

    /** `Command::BleDisconnect` — tear down exactly one physical link. */
    data class Disconnect(
        val deviceId: String,
        val direction: MobileBleLinkDirection,
    ) : BleCommand

    /**
     * `Command::BleWriteCharacteristic` — send [data] on [uuid] over the
     * addressed physical link.
     */
    data class Write(
        val deviceId: String,
        val direction: MobileBleLinkDirection,
        val uuid: String,
        val data: ByteArray,
    ) : BleCommand

    /** `Command::BleReadCharacteristic` — central reads [uuid]. */
    data class Read(
        val deviceId: String,
        val direction: MobileBleLinkDirection,
        val uuid: String,
    ) : BleCommand
}

/**
 * Stopping needs no Bluetooth permission, so it must not raise a prompt. Once
 * commands are queued behind a prompt it still waits its turn: running ahead
 * of a queued start would leave the radio on after replay.
 */
fun BleCommand.mustWaitForPermission(commandsQueued: Boolean): Boolean {
    val isStop = this is BleCommand.StopScanning || this is BleCommand.StopAdvertising
    return commandsQueued || !isStop
}
