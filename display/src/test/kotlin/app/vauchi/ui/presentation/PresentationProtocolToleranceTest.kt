// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Core newer than this shell may emit enum values the shell has never
 * seen. Each one degrades to a rendering fallback instead of aborting the
 * whole envelope — losing one node or one style is proportionate, losing
 * the screen is not.
 */
class PresentationProtocolToleranceTest {
    @Test
    fun `an unknown node kind is dropped while its siblings render`() {
        val surface =
            decodeSurface(
                """
                [{"Text":{"id":"a","content":"before","style":"body","accessibility":{"label":"before","description":null}}},
                 {"Hologram":{"id":"h","frames":3}},
                 {"Text":{"id":"b","content":"after","style":"body","accessibility":{"label":"after","description":null}}}]
                """.trimIndent(),
            )

        assertEquals(listOf("before", "after"), surface.nodes.map { (it as PresentationNode.Text).content })
    }

    @Test
    fun `an unknown node kind inside a group is dropped too`() {
        val surface =
            decodeSurface(
                """
                [{"Group":{"id":"g","label":null,"axis":"vertical","accessibility":{"label":"g","description":null},
                  "children":[{"Hologram":{}}, "Divider"]}}]
                """.trimIndent(),
            )

        assertEquals(listOf(PresentationNode.Divider), (surface.nodes.single() as PresentationNode.Group).children)
    }

    @Test
    fun `an unknown overlay kind decodes as an action menu`() {
        val command =
            PresentationProtocol
                .decodeEnvelope(
                    """{"commands":[{"PresentOverlay":{"surface_id":"main","revision":1,
                       "overlay":{"kind":"drawer","title":null,"items":[]}}}]}""",
                ).commands
                .single() as PresentationCommand.PresentOverlay

        assertEquals(OverlayKind.ActionMenu, command.overlay.kind)
    }

    @Test
    fun `unknown window class and pane layout decode as the compact single pane`() {
        val command =
            PresentationProtocol
                .decodeEnvelope(
                    """{"commands":[{"SetPresentationProfile":{"profile":{
                       "window_class":"foldable","pane_layout":"triptych",
                       "primary_surface":"main","detail_surface":null,"active_surface":"main"}}}]}""",
                ).commands
                .single() as PresentationCommand.SetProfile

        assertEquals(WindowClass.Compact, command.profile.windowClass)
        assertEquals(PaneLayout.Single, command.profile.paneLayout)
    }

    private fun decodeSurface(nodes: String): SurfaceSpec {
        val command =
            PresentationProtocol
                .decodeEnvelope(
                    """
                    {"commands":[{"ReplaceSurface":{"surface":{
                      "surface_id":"main","revision":1,"title":"t","subtitle":null,
                      "accessibility_label":"t","layout":"scroll",
                      "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,"corner_radius":8,"minimum_target_size":44},
                      "nodes":$nodes}}}]}
                    """.trimIndent(),
                ).commands
                .single() as PresentationCommand.ReplaceSurface
        return command.surface
    }
}
