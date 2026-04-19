// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.content.Context
import app.vauchi.proximity.AudioProximityService
import kotlin.math.PI
import kotlin.math.sin

class ExistingCodeDiagnostic(
    context: Context,
) {
    private val audioService = AudioProximityService.getInstance(context)
    private val sampleRate = 44100
    private val testFrequencies = listOf(18500, 19500, 20500, 21000)

    fun runLoopbackTest(log: MutableList<String>) {
        log.add("--- Test A: Loopback (existing code) ---")
        log.add("Capability: ${audioService.checkCapability()}")

        DiagnosticLogger.logResult(
            test = "A",
            track = "existing",
            message = "capability=${audioService.checkCapability()}",
        )

        for (freq in testFrequencies) {
            log.add("Testing $freq Hz via AudioProximityService...")

            val durationMs = 3000
            val numSamples = (sampleRate * durationMs) / 1000
            val samples =
                List(numSamples) { i ->
                    sin(2.0 * PI * freq * i / sampleRate).toFloat()
                }

            val emitResult = audioService.emitSignal(samples, sampleRate.toUInt())
            if (emitResult.isNotEmpty()) {
                DiagnosticLogger.logError("A", "existing", "Emit failed at $freq Hz: $emitResult")
                log.add("  FAIL: emit error: $emitResult")
                continue
            }

            val recorded =
                audioService.receiveSignal(
                    timeoutMs = 4000u.toULong(),
                    sampleRate = sampleRate.toUInt(),
                )

            if (recorded.isEmpty()) {
                DiagnosticLogger.logError("A", "existing", "No samples recorded at $freq Hz")
                log.add("  FAIL: no samples recorded")
                continue
            }

            val recordedArray = recorded.toFloatArray()
            val snr = FftAnalyzer.computeSnrDb(recordedArray, freq, sampleRate)
            val mag = FftAnalyzer.goertzelMagnitudeDb(recordedArray, freq, sampleRate)
            val detected = snr >= 15.0

            DiagnosticLogger.logResult(
                test = "A",
                track = "existing",
                frequencyHz = freq,
                snrDb = snr,
                magnitudeDb = mag,
                detected = detected,
            )
            log.add("  ${freq}Hz: SNR=${"%.1f".format(snr)}dB, mag=${"%.1f".format(mag)}dBFS ${if (detected) "PASS" else "FAIL"}")
        }
    }

    fun runNoiseFloorTest(log: MutableList<String>) {
        log.add("--- Test B: Noise Floor (existing code) ---")

        val recorded =
            audioService.receiveSignal(
                timeoutMs = 5000u.toULong(),
                sampleRate = sampleRate.toUInt(),
            )

        if (recorded.isEmpty()) {
            log.add("FAIL: no samples recorded")
            return
        }

        val recordedArray = recorded.toFloatArray()
        val bins = FftAnalyzer.analyzeBand(recordedArray, 16000, 22000, 100, sampleRate)
        val maxBin = bins.maxByOrNull { it.magnitudeDb }
        val pass = bins.all { it.magnitudeDb < -30.0 }

        for (bin in bins) {
            DiagnosticLogger.logResult(
                test = "B",
                track = "existing",
                frequencyHz = bin.frequencyHz,
                magnitudeDb = bin.magnitudeDb,
            )
        }

        log.add("Max bin: ${maxBin?.frequencyHz}Hz at ${"%.1f".format(maxBin?.magnitudeDb)}dBFS")
        log.add("Result: ${if (pass) "PASS (all <-30 dBFS)" else "FAIL (noise above -30 dBFS)"}")
    }
}
