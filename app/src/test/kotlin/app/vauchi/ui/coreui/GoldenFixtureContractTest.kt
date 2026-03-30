// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests: verify Android's Kotlin decoders stay compatible
 * with core's golden JSON fixtures.
 *
 * Fixtures are copies of `core/vauchi-core/tests/fixtures/golden/`.
 * If core changes the ScreenModel format, these tests catch the drift.
 *
 * RULE: No test in this file may reference a specific core action ID,
 * localized string, or design token value. Assertions are structural only.
 */
class GoldenFixtureContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Fixture loading ────────────────────────────────────────────

    private fun fixturesDir(): String = "/golden"

    /** Discover all .json fixture files dynamically — no hardcoded list. */
    private fun discoverFixtureNames(): List<String> {
        val listing =
            javaClass.getResource(fixturesDir())
                ?: error("Golden fixtures directory not found at ${fixturesDir()}")
        val dir = java.io.File(listing.toURI())
        return dir
            .listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: error("No golden fixtures found")
    }

    private fun loadFixture(name: String): String {
        val path = "${fixturesDir()}/$name.json"
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Fixture not found: $path")
    }

    // ── Phase 2.1: Contract decode tests ───────────────────────────

    @Test
    fun `all golden fixtures decode as ScreenModel`() {
        val fixtures = discoverFixtureNames()
        assertTrue(
            "Expected at least 20 golden fixtures, found ${fixtures.size}",
            fixtures.size >= 20,
        )

        for (name in fixtures) {
            val raw = loadFixture(name)
            val screen = json.decodeFromString<ScreenModel>(raw)
            assertFalse(
                "Fixture '$name': screen_id must not be empty",
                screen.screenId.isEmpty(),
            )
            // title may be empty for placeholder screens (e.g., home_empty)
        }
    }

    @Test
    fun `all fixtures have non-empty components`() {
        for (name in discoverFixtureNames()) {
            val screen = json.decodeFromString<ScreenModel>(loadFixture(name))
            assertFalse(
                "Fixture '$name': components must not be empty",
                screen.components.isEmpty(),
            )
        }
    }

    @Test
    fun `all fixtures have non-empty actions`() {
        for (name in discoverFixtureNames()) {
            val screen = json.decodeFromString<ScreenModel>(loadFixture(name))
            // Some screens (lock, home_empty) may have no actions
            for (action in screen.actions) {
                assertFalse(
                    "Fixture '$name': action label must not be empty",
                    action.label.isEmpty(),
                )
                assertFalse(
                    "Fixture '$name': action id must not be empty",
                    action.id.isEmpty(),
                )
            }
        }
    }

    // ── Phase 2.5: Unknown component resilience ────────────────────

    @Test
    fun `fixture with unknown component type decodes gracefully`() {
        val raw =
            """
            {
                "screen_id": "future_screen",
                "title": "Future",
                "components": [
                    {"Text": {"id": "t1", "content": "Hello", "style": "Body"}},
                    {"FutureWidget": {"id": "fw1", "data": "test"}},
                    "Divider"
                ],
                "actions": [{"id": "ok", "label": "OK", "style": "Primary", "enabled": true}]
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(raw)
        assertEquals(3, screen.components.size)
        assertTrue(screen.components[0] is Component.Text)
        assertTrue(screen.components[1] is Component.Unknown)
        assertTrue(screen.components[2] is Component.Divider)
    }

    // ── Phase 2.6: Version linkage ─────────────────────────────────

    @Test
    fun `version metadata file exists and is valid`() {
        val raw =
            javaClass
                .getResourceAsStream("/golden/.version")
                ?.bufferedReader()
                ?.readText()
                ?: error(".version file not found in golden fixtures")

        val meta = json.decodeFromString<JsonObject>(raw)
        assertNotNull(
            ".version must have core_version",
            meta["core_version"]?.jsonPrimitive?.content,
        )
        assertTrue(
            ".version schema_version must be >= 1",
            meta["schema_version"]!!.jsonPrimitive.int >= 1,
        )

        // fixture_count must match actual .json files
        val fixtureCount = discoverFixtureNames().size
        assertEquals(
            ".version fixture_count must match actual fixture count",
            fixtureCount,
            meta["fixture_count"]!!.jsonPrimitive.int,
        )
    }

    // ── Structural component checks ────────────────────────────────

    @Test
    fun `all Text components have non-empty content`() {
        for (name in discoverFixtureNames()) {
            val screen = json.decodeFromString<ScreenModel>(loadFixture(name))
            for (component in screen.components) {
                if (component is Component.Text) {
                    assertFalse(
                        "Fixture '$name': Text.content must not be empty",
                        component.content.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `no fixture produces Unknown components`() {
        for (name in discoverFixtureNames()) {
            val screen = json.decodeFromString<ScreenModel>(loadFixture(name))
            for (component in screen.components) {
                assertFalse(
                    "Fixture '$name': unexpected Unknown component — core may have added a new type",
                    component is Component.Unknown,
                )
            }
        }
    }
}
