// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

// TODO: Remove this stub when vauchi-platform >= 0.2.0 publishes MobileExchangeHardwareEvent.
// Replace import in BleExchangeService.kt with: uniffi.vauchi_platform.MobileExchangeHardwareEvent

/**
 * Temporary local stub for the ADR-031 hardware event type.
 * Mirrors the UniFFI-generated sealed class from vauchi-core.
 */
sealed class MobileExchangeHardwareEvent {
    data class BleDeviceDiscovered(
        val id: String,
        val rssi: Short,
        val advData: ByteArray,
    ) : MobileExchangeHardwareEvent()

    data class BleConnected(
        val deviceId: String,
    ) : MobileExchangeHardwareEvent()

    data class BleDisconnected(
        val reason: String,
    ) : MobileExchangeHardwareEvent()

    data class BleCharacteristicRead(
        val uuid: String,
        val data: ByteArray,
    ) : MobileExchangeHardwareEvent()

    data class BleCharacteristicNotified(
        val uuid: String,
        val data: ByteArray,
    ) : MobileExchangeHardwareEvent()

    data class HardwareError(
        val transport: String,
        val message: String,
    ) : MobileExchangeHardwareEvent()

    data class HardwareUnavailable(
        val transport: String,
    ) : MobileExchangeHardwareEvent()
}
