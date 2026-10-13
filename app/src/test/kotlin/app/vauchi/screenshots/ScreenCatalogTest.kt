// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Core's screen catalogue is a list of command batches, one per app
 * screen, in the exact wire shape `presentation_contract_v1.json` uses.
 * Each batch must decode through the production parser and reduce from an
 * empty state into the surface, bar, and navigation the renderer needs.
 */
class ScreenCatalogTest {
    private val catalog =
        ScreenCatalog.decode(
            checkNotNull(javaClass.getResource("/screen_catalog_smoke.json")).readText(),
        )

    @Test
    fun `every catalogue entry reduces to its own surface`() {
        assertEquals(listOf("onboarding_welcome", "onboarding_name"), catalog.map { it.codeId })
        assertEquals(
            listOf("Welcome to Vauchi", "What's your name?"),
            catalog.map {
                it.state.surfaces
                    .getValue(it.activeSurfaceId)
                    .title
            },
        )
        assertEquals(
            listOf(1uL, 2uL),
            catalog.map {
                it.state.surfaces
                    .getValue(it.activeSurfaceId)
                    .revision
            },
        )
    }

    @Test
    fun `entry metadata survives decoding`() {
        val entry = catalog.first()
        assertEquals("Welcome to Vauchi", entry.title)
        assertEquals("en", entry.locale)
    }

    @Test
    fun `context bar and navigation are attached to the reduced surface`() {
        for (entry in catalog) {
            assertNotNull(entry.state.activeBar?.primary, "${entry.codeId} lost its primary action")
            assertNotNull(entry.state.activeNavigation, "${entry.codeId} lost its navigation")
        }
    }

    @Test
    fun `a batch without a presentation profile still names an active surface`() {
        for (entry in catalog) {
            assertEquals("onboarding", entry.activeSurfaceId)
            assertEquals(entry.activeSurfaceId, entry.profile.activeSurface)
        }
    }

    // A sub-screen entry also replaces its parent. With no profile the app
    // takes the alphabetically first surface, so the catalogue drew Settings
    // for Settings > Advanced (vauchi/private#420). Core replaces the entry's
    // own surface last; the catalogue, not the app, relies on that.
    @Test
    fun `a sub-screen entry shows its own surface`() {
        val entry =
            ScreenCatalog
                .decode(
                    """
                    {"schema_version":1,"screens":[{"code_id":"settings_advanced",
                      "title":"Advanced","locale":"en","commands":[
                        {"ReplaceSurface":{"surface":${surface("settings")}}},
                        {"ReplaceSurface":{"surface":${surface("settings_advanced")}}}
                    ]}]}
                    """.trimIndent(),
                ).single()

        assertEquals("settings_advanced", entry.activeSurfaceId)
        assertEquals(listOf("settings_advanced"), entry.state.visibleSurfaceIds)
    }

    private fun surface(id: String): String =
        """
        {"surface_id":"$id","revision":1,"title":"$id","subtitle":null,
         "accessibility_label":"$id","layout":"scroll",
         "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,
                   "corner_radius":8,"minimum_target_size":44},
         "nodes":[]}
        """.trimIndent()
}
