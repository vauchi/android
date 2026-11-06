// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

internal data class CaptureReadReport(
    val samples: Long,
    val nonZeroSamples: Long,
    val firstSampleMs: Double?,
    val zeroReads: Int,
    val negativeReads: List<Int>,
)

internal class CaptureReadMeter(
    private val startNanos: Long,
) {
    private var samples = 0L
    private var nonZeroSamples = 0L
    private var firstSampleMs: Double? = null
    private var zeroReads = 0
    private val negativeReads = mutableListOf<Int>()

    @Synchronized
    fun record(
        returnedAtNanos: Long,
        count: Int,
        nonZero: Int,
    ) {
        when {
            count > 0 -> {
                if (firstSampleMs == null) firstSampleMs = (returnedAtNanos - startNanos) / 1_000_000.0
                samples += count
                nonZeroSamples += nonZero
            }

            count == 0 -> {
                zeroReads++
            }

            else -> {
                negativeReads.add(count)
            }
        }
    }

    @Synchronized
    fun snapshot() = CaptureReadReport(samples, nonZeroSamples, firstSampleMs, zeroReads, negativeReads.toList())
}
