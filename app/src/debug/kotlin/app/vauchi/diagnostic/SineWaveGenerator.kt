// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import kotlin.math.PI
import kotlin.math.sin

object SineWaveGenerator {
    fun generate(
        frequencyHz: Int,
        durationMs: Int,
        sampleRate: Int = 44100,
    ): FloatArray {
        val numSamples = (sampleRate * durationMs) / 1000
        val samples = FloatArray(numSamples)
        val angularFreq = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until numSamples) {
            samples[i] = sin(angularFreq * i).toFloat()
        }
        return samples
    }

    fun generateSweep(
        startHz: Int,
        endHz: Int,
        stepHz: Int,
        stepDurationMs: Int,
        sampleRate: Int = 44100,
    ): Pair<FloatArray, List<Pair<Int, Int>>> {
        val frequencies = (startHz..endHz step stepHz).toList()
        val samplesPerStep = (sampleRate * stepDurationMs) / 1000
        val totalSamples = frequencies.size * samplesPerStep
        val samples = FloatArray(totalSamples)
        val markers = mutableListOf<Pair<Int, Int>>()
        for ((index, freq) in frequencies.withIndex()) {
            val offset = index * samplesPerStep
            markers.add(freq to offset)
            val angularFreq = 2.0 * PI * freq / sampleRate
            for (i in 0 until samplesPerStep) {
                samples[offset + i] = sin(angularFreq * i).toFloat()
            }
        }
        return samples to markers
    }
}
