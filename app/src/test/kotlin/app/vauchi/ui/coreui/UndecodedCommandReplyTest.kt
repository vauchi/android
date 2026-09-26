// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import uniffi.vauchi_platform.MobileEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Core's wakeup tick returns surface commands next to hardware ones, and it
 * also fires presentation-invalidated, so the shell reloads the surface
 * anyway. Answering those surface commands with HardwareUnavailable told the
 * engine "hardware missing" a few times a second during a Hover transfer and
 * surfaced "This feature is not available on this device" (vauchi/private#9).
 */
class UndecodedCommandReplyTest {
    // @scenario: generic_presentation_protocol.feature :: Every shell renders the same prepared presentation
    @Test
    fun `surface commands arriving on the exchange channel get no hardware reply`() {
        for (variant in listOf(
            "ReplaceSurface",
            "SetContextBar",
            "SetNavigation",
            "PresentOverlay",
            "DismissOverlay",
            "SetPresentationProfile",
        )) {
            assertNull(undecodedCommandReply(variant), "$variant is a surface command, not hardware")
        }
    }

    // @internal
    @Test
    fun `an unknown hardware command is still answered as unavailable`() {
        assertEquals(
            MobileEvent.HardwareUnavailable("FilePickFromUser"),
            undecodedCommandReply("FilePickFromUser"),
        )
    }
}
