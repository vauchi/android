// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Window-move policy for windowed `Component::List` emissions (Track B,
 * 2026-06-11-contacts-list-eager-render-anr): with a 200-row window and
 * a 50-row prefetch margin, the renderer requests a re-slice when the
 * visible rows approach either edge of the loaded window, keeping the
 * visible region inside the new window so scroll anchoring holds.
 */
class ListWindowPrefetchTest {
    @Test
    fun `mid-window scroll requests nothing`() {
        assertNull(
            listWindowTarget(
                firstVisible = 80,
                lastVisible = 100,
                offset = 0,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `approaching the bottom edge requests a forward window`() {
        assertEquals(
            100,
            listWindowTarget(
                firstVisible = 130,
                lastVisible = 150,
                offset = 0,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `forward request clamps to the last full window`() {
        assertEquals(
            300,
            listWindowTarget(
                firstVisible = 440,
                lastVisible = 460,
                offset = 250,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `at the tail there is no forward request`() {
        assertNull(
            listWindowTarget(
                firstVisible = 460,
                lastVisible = 480,
                offset = 300,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `approaching the top edge requests a backward window`() {
        assertEquals(
            90,
            listWindowTarget(
                firstVisible = 240,
                lastVisible = 260,
                offset = 200,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `backward request clamps to zero`() {
        assertEquals(
            0,
            listWindowTarget(
                firstVisible = 60,
                lastVisible = 80,
                offset = 50,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `at the top of the first window there is no backward request`() {
        assertNull(
            listWindowTarget(
                firstVisible = 0,
                lastVisible = 20,
                offset = 0,
                window = 200,
                totalCount = 500,
            ),
        )
    }

    @Test
    fun `unwindowed emissions never request`() {
        assertNull(
            listWindowTarget(
                firstVisible = 0,
                lastVisible = 150,
                offset = 0,
                window = 0,
                totalCount = 0,
            ),
        )
    }
}
