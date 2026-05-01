// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.content.Context
import android.util.Log
import app.vauchi.ble.BleExchangeService
import app.vauchi.proximity.AudioProximityService
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
    private val context: Context,
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

    private val audioService = AudioProximityService.getInstance(context)

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

            // ── Audio (ultrasonic proximity, ADR-031) ───────────────
            is MobileExchangeCommand.AudioEmitChallenge -> {
                emitAudioChallenge(command.samples, command.sampleRate)
            }

            is MobileExchangeCommand.AudioListenForResponse -> {
                listenForAudioResponse(command.timeoutMs, command.sampleRate)
            }

            is MobileExchangeCommand.AudioStop -> {
                audioService.stop()
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
                bleService.writeCharacteristic(command.uuid, command.data)
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

            // ── USB cable (DirectSend) ───────────────────────────────
            is MobileExchangeCommand.DirectSend -> {
                val service = DirectSendService()
                if (!command.isInitiator) {
                    service.setContext(context)
                }
                service.exchange(
                    payload = command.payload,
                    isInitiator = command.isInitiator,
                    callback =
                        object : DirectSendService.Callback {
                            override fun onPayloadReceived(data: ByteArray) {
                                try {
                                    session.applyHardwareEvent(
                                        MobileExchangeHardwareEvent.DirectPayloadReceived(
                                            data = data,
                                        ),
                                    )
                                    drainAndDispatch()
                                } catch (e: Exception) {
                                    reportError("USB", e.message ?: "apply event failed")
                                }
                            }

                            override fun onError(error: String) {
                                reportError("USB", error)
                            }
                        },
                )
            }

            // ── Tier 0 commands (active after bindings bump) ────────
            // AccelerometerStart, AccelerometerStop, RelayEscrowDeposit,
            // RelayEscrowCheck, RelayEscrowRetrieve, ShowShareSheet
            // are handled via the else branch until UniFFI regeneration.
            else -> {
                Log.d(TAG, "Unhandled command: $command (pending bindings bump)")
            }
        }
    }

    // ── Audio (ADR-031 command/event protocol) ──────────────────────

    private fun emitAudioChallenge(
        samples: List<Float>,
        sampleRate: UInt,
    ) {
        // emitSignal blocks for the playback duration, so dispatch off the
        // command-drain thread to avoid stalling other commands.
        Thread({
            audioService.emitSignal(samples, sampleRate)
        }, "AudioEmitChallenge").apply { isDaemon = true }.start()
    }

    private fun listenForAudioResponse(
        timeoutMs: ULong,
        sampleRate: UInt,
    ) {
        audioService.receiveSignal(timeoutMs, sampleRate) { recordedSamples, recordedRate ->
            try {
                session.applyHardwareEvent(
                    MobileExchangeHardwareEvent.AudioSamplesRecorded(recordedSamples, recordedRate),
                )
                drainAndDispatch()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply audio event: $e")
            }
        }
    }

    // ── Relay Escrow ────────────────────────────────────────────────

    private fun depositToEscrow(
        gateHash: List<UByte>,
        slotHash: List<UByte>,
        blob: List<UByte>,
        ttl: UInt,
    ) {
        // TODO: POST to relay OHTTP endpoint with EscrowMessage::Put
        // On success: no event needed (fire-and-forget deposit)
        // On failure: report RelayEscrowFailed
        reportError("RelayEscrow", "not yet implemented")
    }

    private fun checkEscrow(gateHash: List<UByte>) {
        // TODO: POST to relay OHTTP endpoint with EscrowMessage::Count
        // When count >= 2: report RelayEscrowReady
        // Otherwise: schedule retry after delay
        reportError("RelayEscrow", "not yet implemented")
    }

    private fun retrieveFromEscrow(
        gateHash: List<UByte>,
        slotHash: List<UByte>,
    ) {
        // TODO: POST to relay OHTTP endpoint with EscrowMessage::Get
        // On Blob response: pass blob back to core for decryption
        // On error: report RelayEscrowFailed
        reportError("RelayEscrow", "not yet implemented")
    }

    // ── Share Sheet ────────────────────────────────────────────────

    private fun showShareSheet(url: String) {
        // TODO: Create Intent.ACTION_SEND with the exchange URL
        // On completion: report LinkShared event via session.applyHardwareEvent
        Log.d(TAG, "ShareSheet command received")
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

    fun reportPermissionDenied(transport: String) {
        try {
            session.applyHardwareEvent(
                MobileExchangeHardwareEvent.PermissionDenied(transport),
            )
            drainAndDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report permission denied: $e")
        }
    }
}
