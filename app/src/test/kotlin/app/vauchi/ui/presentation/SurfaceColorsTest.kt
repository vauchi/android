// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import app.vauchi.ui.theme.statusColors
import app.vauchi.ui.theme.toColorScheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shell paints the active theme's colours Core sends on every surface
 * and tells Core whether the OS is light or dark (vauchi/private#814).
 */
class SurfaceColorsTest {
    // @internal
    @Test
    fun `a surface carries the theme colours Core sent`() {
        val colors = decodeSurfaceColors(DRACULA)

        assertEquals("#282a36", colors?.background)
        assertEquals("#44475a", colors?.surface)
        assertEquals("#f8f8f2", colors?.text)
        assertEquals("#bd93f9", colors?.accent)
        assertEquals("#282a36", colors?.textOnAccent)
        assertNull(colors?.surfaceMuted)
    }

    // @internal
    @Test
    fun `a surface without colours keeps the platform defaults`() {
        assertNull(decodeSurfaceColors(colors = null))
    }

    // @internal
    @Test
    fun `the environment event names the OS appearance`() {
        for ((dark, wire) in listOf(true to "\"dark\"", false to "\"light\"")) {
            val event =
                PresentationEvent.environmentChanged(
                    width = 400,
                    height = 800,
                    inputModes = listOf(InputMode.Touch),
                    reducedMotion = false,
                    darkAppearance = dark,
                )
            val environment =
                Json
                    .parseToJsonElement(event.toJson())
                    .jsonObject
                    .getValue("PresentationEnvironmentChanged")
                    .jsonObject

            assertEquals(wire, environment.getValue("color_scheme").toString())
        }
    }

    // @internal
    @Test
    fun `the colour scheme paints each role from Core's colours`() {
        val scheme = decodeSurfaceColors(DRACULA)!!.toColorScheme(darkColorScheme())

        assertEquals(Color(0xFF282A36), scheme.background)
        assertEquals(Color(0xFF44475A), scheme.surface)
        assertEquals(Color(0xFF44475A), scheme.surfaceVariant)
        assertEquals(Color(0xFFF8F8F2), scheme.onBackground)
        assertEquals(Color(0xFFF8F8F2), scheme.onSurface)
        assertEquals(Color(0xFF6272A4), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFBD93F9), scheme.primary)
        assertEquals(Color(0xFF282A36), scheme.onPrimary)
        assertEquals(Color(0xFF6272A4), scheme.outline)
        assertEquals(Color(0xFFFF5555), scheme.error)
    }

    // @internal
    @Test
    fun `status colours come from Core's colours`() {
        val status = decodeSurfaceColors(DRACULA)!!.statusColors()

        assertEquals(Color(0xFF50FA7B), status.success)
        assertEquals(Color(0xFFF1FA8C), status.warning)
        assertEquals(Color(0xFFBD93F9), status.info)
    }

    private fun decodeSurfaceColors(colors: String?): PresentationColors? {
        val colorsField = colors?.let { ""","colors":$it""" }.orEmpty()
        val envelope =
            """
            {"commands":[{"ReplaceSurface":{"surface":{
              "surface_id":"main","revision":1,"title":"T","subtitle":null,
              "accessibility_label":"T","layout":"scroll",
              "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,
                        "corner_radius":8,"minimum_target_size":44$colorsField},
              "nodes":[]}}}]}
            """.trimIndent()
        val state =
            PresentationReducer
                .apply(PresentationState(), PresentationProtocol.decodeEnvelope(envelope).commands)
                .state
        return state.surfaces
            .getValue("main")
            .tokens.colors
    }

    private companion object {
        const val DRACULA =
            """{"background":"#282a36","surface":"#44475a","text":"#f8f8f2",
            "text_muted":"#6272a4","accent":"#bd93f9","border":"#6272a4",
            "success":"#50fa7b","warning":"#f1fa8c","error":"#ff5555",
            "surface_input":null,"surface_muted":null,"surface_elevated":null,
            "text_on_accent":"#282a36","focus_ring":null}"""
    }
}
