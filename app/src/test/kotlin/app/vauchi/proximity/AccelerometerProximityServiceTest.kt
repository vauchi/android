// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import app.vauchi.proximity.AccelerometerProximityService.Companion.STANDARD_GRAVITY
import app.vauchi.proximity.AccelerometerProximityService.Companion.axisToMilliG
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure milli-g conversion the service applies to raw
 * `SensorEvent` readings before handing them to core. The sensor wiring
 * (register/unregister) is Android-framework glue; the conversion is the
 * load-bearing logic and must match core's envelope shape (1 g = 1000 milli-g,
 * clamped at 8 g).
 */
class AccelerometerProximityServiceTest {
    @Test
    fun `gravity converts to 1000 milli-g`() {
        assertEquals(1000, axisToMilliG(STANDARD_GRAVITY))
    }

    @Test
    fun `zero acceleration converts to 0 milli-g`() {
        assertEquals(0, axisToMilliG(0f))
    }

    @Test
    fun `half gravity converts to 500 milli-g`() {
        assertEquals(500, axisToMilliG(STANDARD_GRAVITY / 2f))
    }

    @Test
    fun `two g converts to 2000 milli-g`() {
        assertEquals(2000, axisToMilliG(2f * STANDARD_GRAVITY))
    }

    @Test
    fun `negative acceleration converts to negative milli-g`() {
        assertEquals(-1000, axisToMilliG(-STANDARD_GRAVITY))
    }
}
