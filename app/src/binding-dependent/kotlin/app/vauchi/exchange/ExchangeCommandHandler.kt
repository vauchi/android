// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.content.Context
import android.util.Log
import app.vauchi.ble.BleExchangeService
import uniffi.vauchi_platform.MobileExchangeCommand
import uniffi.vauchi_platform.MobileExchangeHardwareEvent
import uniffi.vauchi_platform.MobileExchangeSession

/**
 * Dispatches ADR-031 exchange commands from core to Android platform services.
 *
 * After each state-advancing call on [MobileExchangeSession], drain pending
 * commands and pass them here. Results are reported back via
 * [MobileExchangeSession.applyHardwareEvent].
 */
class ExchangeCommandHandler(
    private val session: MobileExchangeSession,
    context: Context,
) {
    private val bleService =
        BleExchangeService(context) { event ->
            try {
                session.applyHardwareEvent(event)
                drainAndDispatch()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply BLE event: $e")
            }
        }

    companion object {
        private const val TAG = "ExchangeCmd"
    }

    /**
     * Drain and dispatch all pending commands from the session.
     *
     * Call after `generateQr()`, `processQr()`, `performKeyAgreement()`, etc.
     */
    fun drainAndDispatch() {
        val commands = session.drainPendingCommands()
        for (command in commands) {
            dispatch(command)
        }
    }

    private fun dispatch(command: MobileExchangeCommand) {
        when (command) {
            // ── QR ──────────────────────────────────────────────────
            is MobileExchangeCommand.QrDisplay -> {
                // QR display handled by Compose view layer
            }

            is MobileExchangeCommand.QrRequestScan -> {
                // Camera scanning handled by CameraX in the view layer
            }

            // ── Audio (ultrasonic proximity) ────────────────────────
            is MobileExchangeCommand.AudioEmitChallenge -> {
                emitAudioChallenge(command.data)
            }

            is MobileExchangeCommand.AudioListenForResponse -> {
                listenForAudioResponse(command.timeoutMs)
            }

            is MobileExchangeCommand.AudioStop -> {
                // Audio operations are one-shot — no persistent state to stop
            }

            // ── BLE (native Android) ──────────────────────────────────
            is MobileExchangeCommand.BleStartScanning -> {
                bleService.startScanning(command.serviceUuid)
            }

            is MobileExchangeCommand.BleStartAdvertising -> {
                // Android peripheral advertising not yet wired
                reportUnavailable("BLE-advertise")
            }

            is MobileExchangeCommand.BleConnect -> {
                bleService.connect(command.deviceId)
            }

            is MobileExchangeCommand.BleWriteCharacteristic -> {
                bleService.writeCharacteristic(command.uuid, command.data.map { it.toByte() }.toByteArray())
            }

            is MobileExchangeCommand.BleReadCharacteristic -> {
                bleService.readCharacteristic(command.uuid)
            }

            is MobileExchangeCommand.BleDisconnect -> {
                bleService.disconnect()
            }

            // ── NFC ─────────────────────────────────────────────────
            is MobileExchangeCommand.NfcActivate -> {
                // NFC handled separately via NfcReaderService (IsoDep)
                reportUnavailable("NFC-command")
            }

            is MobileExchangeCommand.NfcDeactivate -> {
                // No-op
            }
        }
    }

    // ── Audio ───────────────────────────────────────────────────────

    private fun emitAudioChallenge(data: List<UByte>) {
        try {
            val samples = data.map { it.toFloat() / 255f }
            // AudioProximityService handles emission via AudioTrack
            Log.d(TAG, "Emitting audio challenge (${data.size} bytes)")
            // TODO: Wire to AudioProximityService.emitSignal() when
            // the service supports the raw sample API
        } catch (e: Exception) {
            reportError("Audio", e.message ?: "emit failed")
        }
    }

    private fun listenForAudioResponse(timeoutMs: ULong) {
        try {
            Log.d(TAG, "Listening for audio response (timeout=${timeoutMs}ms)")
            // TODO: Wire to AudioProximityService.receiveSignal() when
            // the service supports the raw sample API.
            // On success: session.applyHardwareEvent(AudioResponseReceived(data))
            // On failure: reportError("Audio", error)
        } catch (e: Exception) {
            reportError("Audio", e.message ?: "listen failed")
        }
    }

    // ── Feedback ────────────────────────────────────────────────────

    private fun reportUnavailable(transport: String) {
        try {
            session.applyHardwareEvent(
                MobileExchangeHardwareEvent.HardwareUnavailable(transport),
            )
            drainAndDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report unavailable: $e")
        }
    }

    private fun reportError(
        transport: String,
        error: String,
    ) {
        try {
            session.applyHardwareEvent(
                MobileExchangeHardwareEvent.HardwareError(transport, error),
            )
            drainAndDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report error: $e")
        }
    }
}
