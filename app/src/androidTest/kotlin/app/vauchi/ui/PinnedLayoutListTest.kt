// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.vauchi.ui.coreui.Component
import app.vauchi.ui.coreui.Item
import app.vauchi.ui.coreui.ScreenLayout
import app.vauchi.ui.coreui.ScreenModel
import app.vauchi.ui.coreui.ScreenRenderer
import app.vauchi.ui.theme.VauchiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val TEST_CONTACT_0 = "Contact 0"
private const val TEST_CONTACT_499 = "Contact 499"
private const val TEST_CONTACT_PREFIX = "Contact "
private const val TEST_CONTACTS_TITLE = "Contacts"

/**
 * Pins the `ScreenLayout.Pinned` contract on the renderer: the list
 * component becomes the lazy scroll host (bounded composition) while
 * screen chrome stays pinned. Eager rendering of every row at 10k
 * contacts crashed Compose and ANR'd
 * (`2026-06-11-contacts-list-eager-render-anr`; design
 * `2026-06-11-contacts-list-windowing-design.md`).
 */
class PinnedLayoutListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun items(count: Int): List<Item> =
        (0 until count).map { i ->
            Item(
                id = "contact-$i",
                name = "$TEST_CONTACT_PREFIX$i",
                subtitle = null,
                avatarInitials = "C$i",
            )
        }

    private fun screen(
        layout: ScreenLayout,
        itemCount: Int,
    ): ScreenModel =
        ScreenModel(
            screenId = "contacts",
            title = TEST_CONTACTS_TITLE,
            components =
                listOf(
                    Component.List(
                        id = "contacts",
                        items = items(itemCount),
                        searchable = false,
                    ),
                ),
            actions = emptyList(),
            layout = layout,
        )

    @Test
    fun pinned_layout_composes_bounded_subset_of_large_list() {
        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = screen(ScreenLayout.Pinned, itemCount = 500), onAction = {})
            }
        }

        // Lazy host: far-away rows must not exist in the tree.
        composeTestRule.onNodeWithText(TEST_CONTACT_0).assertExists()
        composeTestRule.onNodeWithText(TEST_CONTACT_499).assertDoesNotExist()
        val composed =
            composeTestRule
                .onAllNodesWithText(TEST_CONTACT_PREFIX, substring = true)
                .fetchSemanticsNodes()
                .size
        assertTrue("expected bounded composition, got $composed rows", composed < 100)
    }

    @Test
    fun pinned_layout_list_scrolls_to_distant_row() {
        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = screen(ScreenLayout.Pinned, itemCount = 500), onAction = {})
            }
        }

        composeTestRule
            .onNodeWithTag("pinned_list")
            .performScrollToNode(hasText(TEST_CONTACT_499))
        composeTestRule.onNodeWithText(TEST_CONTACT_499).assertExists()
    }

    @Test
    fun pinned_layout_keeps_title_chrome() {
        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = screen(ScreenLayout.Pinned, itemCount = 500), onAction = {})
            }
        }

        composeTestRule.onNodeWithText(TEST_CONTACTS_TITLE).assertExists()
    }

    @Test
    fun scroll_layout_keeps_eager_rendering() {
        composeTestRule.setContent {
            VauchiTheme {
                ScreenRenderer(screen = screen(ScreenLayout.Scroll, itemCount = 30), onAction = {})
            }
        }

        // Eager path: every row exists in the tree even off-viewport.
        val composed =
            composeTestRule
                .onAllNodesWithText(TEST_CONTACT_PREFIX, substring = true)
                .fetchSemanticsNodes()
                .size
        assertTrue("expected all 30 rows composed eagerly, got $composed", composed == 30)
    }
}
