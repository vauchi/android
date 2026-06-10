// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent

/**
 * Unit tests for [LocationCaptureService]'s pure outcome mapping
 * (capture-at-exchange, ADR-051). The permission/result mapping is extracted
 * onto the companion so it runs on the plain JVM without a [android.location.LocationManager];
 * the OS permission/provider flow itself is OS-tested (CC-23).
 */
class LocationCaptureServiceTest {
    @Test
    fun no_location_permission_yields_permission_denied() {
        assertEquals(
            MobileEvent.PermissionDenied("location"),
            LocationCaptureService.permissionDeniedEvent(fine = false, coarse = false),
        )
    }

    @Test
    fun fine_permission_proceeds() {
        assertNull(LocationCaptureService.permissionDeniedEvent(fine = true, coarse = false))
    }

    @Test
    fun coarse_permission_proceeds() {
        assertNull(LocationCaptureService.permissionDeniedEvent(fine = false, coarse = true))
    }

    @Test
    fun valid_fix_maps_coordinates_and_accuracy() {
        assertEquals(
            MobileEvent.LocationResult(latitude = 47.3769, longitude = 8.5417, accuracyMeters = 12.5f),
            LocationCaptureService.locationResultEvent(
                latitude = 47.3769,
                longitude = 8.5417,
                hasAccuracy = true,
                accuracy = 12.5f,
            ),
        )
    }

    @Test
    fun fix_without_accuracy_maps_to_null() {
        assertEquals(
            MobileEvent.LocationResult(latitude = 47.3769, longitude = 8.5417, accuracyMeters = null),
            LocationCaptureService.locationResultEvent(
                latitude = 47.3769,
                longitude = 8.5417,
                hasAccuracy = false,
                accuracy = 0f,
            ),
        )
    }
}
