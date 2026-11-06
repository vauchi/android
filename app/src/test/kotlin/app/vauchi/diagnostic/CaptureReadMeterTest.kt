// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureReadMeterTest {
    @Test
    fun `returned silence is data rather than empty capture`() {
        val meter = CaptureReadMeter(1_000_000)
        meter.record(11_000_000, 4, 0)
        val report = meter.snapshot()
        assertEquals(4L, report.samples)
        assertEquals(0L, report.nonZeroSamples)
        assertEquals(10.0, report.firstSampleMs!!, 0.0)
        assertEquals(emptyList<Int>(), report.negativeReads)
    }

    @Test
    fun `negative reads preserve errors without claiming samples`() {
        val meter = CaptureReadMeter(0)
        meter.record(5_000_000, -3, 0)
        val report = meter.snapshot()
        assertEquals(0L, report.samples)
        assertEquals(null, report.firstSampleMs)
        assertEquals(listOf(-3), report.negativeReads)
    }

    @Test
    fun `zero reads do not start the first sample clock`() {
        val meter = CaptureReadMeter(0)
        meter.record(5_000_000, 0, 0)
        meter.record(30_000_000, 2, 2)
        meter.record(40_000_000, 3, 1)
        val report = meter.snapshot()
        assertEquals(5L, report.samples)
        assertEquals(3L, report.nonZeroSamples)
        assertEquals(1, report.zeroReads)
        assertEquals(30.0, report.firstSampleMs!!, 0.0)
    }
}
