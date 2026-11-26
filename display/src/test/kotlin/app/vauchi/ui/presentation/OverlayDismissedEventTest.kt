// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import app.vauchi.contract.ContractJson
import app.vauchi.contract.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import app.vauchi.contract.OverlayKind as ContractOverlayKind

// Feature: generic_presentation_protocol.feature — preserve the acknowledged overlay kind.
class OverlayDismissedEventTest {
    @Test
    fun everyNativeOverlayKindKeepsItsCanonicalAcknowledgement() {
        val kinds =
            listOf(
                OverlayKind.Navigation to ContractOverlayKind.Navigation,
                OverlayKind.ActionMenu to ContractOverlayKind.ActionMenu,
                OverlayKind.Information to ContractOverlayKind.Information,
            )
        for ((local, canonical) in kinds) {
            assertEquals(
                Event.OverlayDismissed(canonical, "opaque-surface"),
                ContractJson.decodeEvent(PresentationEvent.OverlayDismissed("opaque-surface", local).toJson()),
            )
        }
    }
}
