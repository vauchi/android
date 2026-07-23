// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLinkAddressTest {
    @Test
    fun matching_device_id_targets_link() {
        assertTrue(BleLinkAddress.matches(requested = "peer-1", actual = "peer-1"))
    }

    @Test
    fun different_device_id_does_not_target_link() {
        assertFalse(BleLinkAddress.matches(requested = "peer-1", actual = "peer-2"))
    }

    @Test
    fun empty_device_id_targets_current_link_for_legacy_teardown() {
        assertTrue(BleLinkAddress.matches(requested = "", actual = "peer-1"))
    }
}
