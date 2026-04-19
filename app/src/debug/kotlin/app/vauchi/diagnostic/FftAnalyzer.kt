// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import kotlin.math.*

object FftAnalyzer {
    data class FrequencyBin(
        val frequencyHz: Int,
        val magnitudeDb: Double,
        val detected: Boolean,
    )

    fun goertzelMagnitudeDb(
        samples: FloatArray,
        targetFreqHz: Int,
        sampleRate: Int,
    ): Double {
        val n = samples.size
        val k = (0.5 + (n.toDouble() * targetFreqHz / sampleRate)).toInt()
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (sample in samples) {
            s0 = sample + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val magnitude = sqrt(abs(power)) / n
        return if (magnitude > 0) 20.0 * log10(magnitude) else -120.0
    }

    fun computeSnrDb(
        samples: FloatArray,
        targetFreqHz: Int,
        sampleRate: Int,
        bandStartHz: Int = 16000,
        bandEndHz: Int = 22000,
        bandStepHz: Int = 100,
    ): Double {
        val targetDb = goertzelMagnitudeDb(samples, targetFreqHz, sampleRate)
        val noiseFreqs = (bandStartHz..bandEndHz step bandStepHz).filter { abs(it - targetFreqHz) > 200 }
        val noiseDb = noiseFreqs.map { goertzelMagnitudeDb(samples, it, sampleRate) }
        val avgNoiseDb = if (noiseDb.isNotEmpty()) noiseDb.average() else -120.0
        return targetDb - avgNoiseDb
    }

    fun analyzeBand(
        samples: FloatArray,
        startHz: Int,
        endHz: Int,
        stepHz: Int,
        sampleRate: Int,
        snrThresholdDb: Double = 15.0,
    ): List<FrequencyBin> =
        (startHz..endHz step stepHz).map { freq ->
            val magnitudeDb = goertzelMagnitudeDb(samples, freq, sampleRate)
            val snrDb = computeSnrDb(samples, freq, sampleRate, startHz, endHz, stepHz)
            FrequencyBin(frequencyHz = freq, magnitudeDb = magnitudeDb, detected = snrDb >= snrThresholdDb)
        }
}
