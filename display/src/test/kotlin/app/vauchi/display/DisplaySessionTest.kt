// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.Command
import app.vauchi.contract.ContractJson
import app.vauchi.contract.Event
import app.vauchi.contract.ToastSpec
import app.vauchi.ui.presentation.PresentationProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DisplaySessionTest {
    @Test
    fun hardwareCameraStopDoesNotCrossTheDisplayCommandBoundary() {
        val session = DisplaySession { }
        assertEquals(setOf(0), session.apply(listOf(Command.CameraViewport("opaque-preview"))))
        val before = session.state.value
        assertEquals(emptySet<Int>(), session.apply(listOf(Command.CameraStop("opaque-preview"))))
        assertEquals(before, session.state.value)
    }

    private fun surface(revision: ULong): Command =
        ContractJson.decodeCommand(
            """{"ReplaceSurface":{"surface":{"surface_id":"surface","revision":$revision,"title":"Title", "subtitle":null,
        "accessibility_label":"Surface","layout":"fixed","nodes":[],
        "tokens":{"spacing_small":8,"spacing_medium":16,"spacing_large":24,"corner_radius":12,"minimum_target_size":48,
        "colors":{"accent":"#123456","background":"#ffffff","border":"#aaaaaa","error":"#ff0000","success":"#008800",
        "surface":"#eeeeee","text":"#000000","text_muted":"#666666","warning":"#ffaa00"}}}}}""",
        )

    @Test
    fun typedContractCommandsPrepareDisplayStateAndLeaveHardwareToItsExecutor() {
        val session = DisplaySession { }
        val commands = listOf(surface(1uL), Command.CameraViewport("camera-opaque"), Command.BleStopScanning)

        assertEquals(setOf(0, 1), session.apply(commands))
        assertEquals(
            "Title",
            session.state.value.presentation.surfaces
                .getValue("surface")
                .title,
        )
        assertEquals(
            1uL,
            session.state.value.presentation.surfaces
                .getValue("surface")
                .revision,
        )
        assertEquals("camera-opaque", session.state.value.previewId)
    }

    @Test
    fun effectsRetainTheirOrderAndOpaqueTokensPreventStaleConsumption() {
        val session = DisplaySession { }
        val first = Command.ShowToast(ToastSpec("first"))
        val second = Command.ShowToast(ToastSpec("second"))

        assertEquals(setOf(0, 1), session.apply(listOf(first, second)))
        assertEquals(listOf(DisplayEffect(1uL, first), DisplayEffect(2uL, second)), session.state.value.effects)
        session.consumeEffect(1uL)
        session.consumeEffect(1uL)
        assertEquals(listOf(DisplayEffect(2uL, second)), session.state.value.effects)
    }

    @Test
    fun aRefusedBatchPreservesBothTheVisibleStateAndPendingEffects() {
        val session = DisplaySession { }
        session.apply(listOf(surface(2uL)))
        val before = session.state.value

        assertThrows(PresentationProtocolException::class.java) {
            session.apply(listOf(Command.ShowToast(ToastSpec("must not escape")), surface(1uL)))
        }
        assertEquals(before, session.state.value)
    }

    @Test
    fun opaqueEventsReachTheHostWithoutDomainInterpretation() {
        val reported = mutableListOf<Event>()
        val session = DisplaySession(reported::add)
        val event = Event.ActionActivated("opaque-action", "opaque-surface")

        session.report(event)

        assertEquals(listOf(event), reported)
    }
}
