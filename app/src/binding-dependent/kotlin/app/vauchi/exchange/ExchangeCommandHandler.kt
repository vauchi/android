// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.content.Context
import android.util.Log
import app.vauchi.ble.BleExchangeService
import app.vauchi.nfc.NfcReaderService
import app.vauchi.proximity.AudioProximityService
import uniffi.vauchi_platform.MobileCommand
import uniffi.vauchi_platform.MobileEvent
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
    bleServiceFactory: (Context, (MobileEvent) -> Unit) -> BleExchangeService =
        { ctx, cb -> BleExchangeService(ctx, cb) },
) {
    private val bleService =
        bleServiceFactory(context) { event ->
            try {
                session.applyHardwareEvent(event)
                drainAndDispatch()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply BLE event: $e")
            }
        }

    private val audioService = AudioProximityService.getInstance(context)

    /**
     * `nfcService` is instantiated on first NFC command; the lifecycle
     * matches one tap (activate → onTagDiscovered → sendApdu* →
     * deactivate). Per the 2026-05-19 NFC engine-graduation Phase 3a
     * plan, this dispatch path emits Event.NfcDataReceived back to core
     * where NfcExchangeFlow (core/vauchi-app/src/ui/exchange_nfc.rs)
     * drives the 3-phase handshake state machine. Service stays a
     * transceive shim per ADR-031 / ADR-043.
     *
     * Reader-mode lifecycle (NfcAdapter.enableReaderMode) is
     * Activity-owned and lives in the screen layer; the dispatch
     * path here is wired-but-dead until Phase 4 retires NfcExchangeScreen
     * (which also requires core ExchangeMode::Nfc + start_nfc_mode
     * entry path). Same stance as iOS Phase 2 (ios!435).
     */
    private val nfcService = NfcReaderService()

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

    private fun dispatch(command: MobileCommand) {
        when (command) {
            // ── QR ──────────────────────────────────────────────────
            is MobileCommand.QrDisplay -> {
                // QR display handled by Compose view layer
            }

            is MobileCommand.QrRequestScan -> {
                // Camera scanning handled by CameraX in the view layer
            }

            // ── Audio (ultrasonic proximity, ADR-031) ───────────────
            is MobileCommand.AudioEmitChallenge -> {
                emitAudioChallenge(command.samples, command.sampleRate)
            }

            is MobileCommand.AudioListenForResponse -> {
                listenForAudioResponse(command.timeoutMs, command.sampleRate)
            }

            is MobileCommand.AudioStop -> {
                audioService.stop()
            }

            // ── BLE (native Android) ──────────────────────────────────
            is MobileCommand.BleStartScanning -> {
                bleService.startScanning(command.serviceUuid)
            }

            is MobileCommand.BleStartAdvertising -> {
                // Android peripheral advertising not yet wired
                reportUnavailable("BLE-advertise")
            }

            is MobileCommand.BleConnect -> {
                bleService.connect(command.deviceId)
            }

            is MobileCommand.BleWriteCharacteristic -> {
                bleService.writeCharacteristic(command.uuid, command.data)
            }

            is MobileCommand.BleReadCharacteristic -> {
                bleService.readCharacteristic(command.uuid)
            }

            is MobileCommand.BleDisconnect -> {
                bleService.disconnect()
            }

            // ── NFC ─────────────────────────────────────────────────
            is MobileCommand.NfcActivate -> {
                nfcService.activate(command.payload) { event ->
                    try {
                        session.applyHardwareEvent(event)
                        drainAndDispatch()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply NFC event: $e")
                    }
                }
            }

            is MobileCommand.NfcSendApdu -> {
                nfcService.sendApdu(command.data)
            }

            is MobileCommand.NfcDeactivate -> {
                nfcService.deactivate()
            }

            // ── USB cable (DirectSend) ───────────────────────────────
            is MobileCommand.DirectSend -> {
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
                                        MobileEvent.DirectPayloadReceived(
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
                    MobileEvent.AudioSamplesRecorded(recordedSamples, recordedRate),
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
                MobileEvent.HardwareUnavailable(transport),
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
                MobileEvent.HardwareError(transport, error),
            )
            drainAndDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report error: $e")
        }
    }

    fun reportPermissionDenied(transport: String) {
        try {
            session.applyHardwareEvent(
                MobileEvent.PermissionDenied(transport),
            )
            drainAndDispatch()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report permission denied: $e")
        }
    }
}
