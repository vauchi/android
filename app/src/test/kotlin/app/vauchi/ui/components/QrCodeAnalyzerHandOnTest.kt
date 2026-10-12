// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileScanResult

/**
 * Facing the other phone, the camera sees the peer's code and a reflection
 * of this phone's own. Core's scanner reports every code in the frame
 * (`decodedAll`) and Core drops the reflection by session id, so the
 * analyzer must hand on all of them, not only the first (vauchi/private#450,
 * plan step 8.4).
 */
class QrCodeAnalyzerHandOnTest {
    private fun scan(
        decoded: String?,
        decodedAll: List<String>,
    ) = MobileScanResult(
        decoded = decoded,
        decodedAll = decodedAll,
        totalUs = 0uL,
        preprocessingUs = 0uL,
        decodeUs = 0uL,
        frameSkipped = false,
        laplacianVariance = 0f,
    )

    @Test
    fun every_code_in_the_frame_is_handed_on_in_the_order_found() {
        val handedOn = mutableListOf<String>()

        handOnDecodedCodes(scan("own-reflection", listOf("own-reflection", "peer")), handedOn::add)

        assertEquals(listOf("own-reflection", "peer"), handedOn)
    }

    @Test
    fun a_frame_without_a_code_hands_on_nothing() {
        val handedOn = mutableListOf<String>()

        handOnDecodedCodes(scan(null, emptyList()), handedOn::add)

        assertEquals(emptyList<String>(), handedOn)
    }
}
