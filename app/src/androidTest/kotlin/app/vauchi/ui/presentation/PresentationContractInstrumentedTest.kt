// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.vauchi_platform.presentationContractFixtureJson
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PresentationContractInstrumentedTest {
    // @scenario: generic_presentation_protocol.feature :: Every shell renders the same prepared presentation
    @Test
    fun sharedPresentationContractReachesExpectedState() {
        val fixture = Json.parseToJsonElement(presentationContractFixtureJson()).jsonObject
        assertEquals("1", fixture.getValue("schema_version").toString())

        var state = PresentationState()

        fun apply(commands: String) {
            val result =
                PresentationReducer.apply(
                    state,
                    PresentationProtocol.decodeEnvelope("""{"commands":$commands}""").commands,
                )
            assertTrue(result.effects.isEmpty())
            state = result.state
        }

        apply(fixture.getValue("initial_commands").jsonArray.toString())
        fixture.getValue("steps").jsonArray.forEach { step ->
            apply(
                step.jsonObject
                    .getValue("commands")
                    .jsonArray
                    .toString(),
            )
        }

        val expected = fixture.getValue("expected_state").jsonObject
        val surfaceId = expected.getValue("active_surface_id").jsonPrimitive.content
        val surface = expected.getValue("surface").jsonObject
        val bar = expected.getValue("context_bar").jsonObject
        val expectedState =
            PresentationReducer
                .apply(
                    PresentationState(),
                    PresentationProtocol
                        .decodeEnvelope(
                            """
                            {"commands":[
                              {"ReplaceSurface":{"surface":$surface}},
                              {"SetContextBar":{
                                "surface_id":"$surfaceId",
                                "revision":${surface.getValue("revision")},
                                "bar":$bar
                              }}
                            ]}
                            """.trimIndent(),
                        ).commands,
                ).state

        assertEquals(surfaceId, state.activeSurfaceId)
        assertEquals(expectedState.surfaces[surfaceId], state.surfaces[surfaceId])
        assertEquals(expectedState.bars[surfaceId], state.bars[surfaceId])
    }
}
