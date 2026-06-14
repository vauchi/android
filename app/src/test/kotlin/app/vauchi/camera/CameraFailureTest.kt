// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent

/**
 * Unit tests for [CameraFailure]'s pure denial-to-event mapping. The OS prompt
 * itself is OS-tested (CC-23); these pin the transport label core matches on
 * to fail the camera leg visibly instead of waiting forever
 * (`2026-06-11-exchange-waits-forever-without-capabilities`, T0.3).
 */
class CameraFailureTest {
    @Test
    fun denial_maps_to_permission_denied_camera() {
        assertEquals(
            MobileEvent.PermissionDenied("camera"),
            CameraFailure.deniedEvent(),
        )
    }

    @Test
    fun transport_label_is_lowercase_camera() {
        assertEquals("camera", CameraFailure.TRANSPORT)
    }
}
