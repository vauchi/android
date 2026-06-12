// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.vauchi.ui.coreui.Component
import app.vauchi.ui.coreui.UserAction
import kotlinx.coroutines.flow.filterNotNull

/**
 * Rows of cushion between the visible region and the loaded window's
 * edge before a re-slice is requested (~50 per the windowing design,
 * 2026-06-11-contacts-list-eager-render-anr Track B).
 */
const val LIST_WINDOW_PREFETCH_MARGIN = 50

/**
 * Window-move policy for windowed list emissions. Returns the offset to
 * request from core, or null when the visible region is comfortably
 * inside the loaded window (or the emission is unwindowed). Targets
 * keep the currently visible rows inside the new window so lazy-list
 * key-based scroll anchoring holds across the re-slice.
 */
fun listWindowTarget(
    firstVisible: Int,
    lastVisible: Int,
    offset: Int,
    window: Int,
    totalCount: Int,
    margin: Int = LIST_WINDOW_PREFETCH_MARGIN,
): Int? {
    if (totalCount <= window) return null
    if (lastVisible >= offset + window - margin && offset + window < totalCount) {
        val target = (lastVisible - margin).coerceIn(0, totalCount - window)
        return target.takeIf { it != offset }
    }
    if (firstVisible <= offset + margin && offset > 0) {
        val target = (firstVisible - (window - margin)).coerceAtLeast(0)
        return target.takeIf { it != offset }
    }
    return null
}

/**
 * Observes [listState] and dispatches [UserAction.ListWindowRequested]
 * when scrolling approaches the edge of [component]'s loaded window.
 * Visible window-relative indices are recovered from the lazy item keys
 * (`list_row:<componentId>:<itemId>`) so leading chrome items never
 * skew the mapping. The effect restarts on every window emission, which
 * also resets the duplicate-request guard.
 */
@Composable
fun ListWindowPrefetch(
    component: Component.List,
    listState: LazyListState,
    onAction: (UserAction) -> Unit,
) {
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(component.id, component.offset, component.items) {
        var lastRequested: Int? = null
        val rowPrefix = "list_row:${component.id}:"
        val rowIndexById =
            component.items
                .withIndex()
                .associate { (index, item) -> item.id to index }
        snapshotFlow {
            val visibleRows =
                listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    (info.key as? String)
                        ?.takeIf { it.startsWith(rowPrefix) }
                        ?.removePrefix(rowPrefix)
                        ?.let { rowIndexById[it] }
                }
            if (visibleRows.isEmpty()) null else visibleRows.min() to visibleRows.max()
        }.filterNotNull()
            .collect { (firstRow, lastRow) ->
                val target =
                    listWindowTarget(
                        firstVisible = component.offset + firstRow,
                        lastVisible = component.offset + lastRow,
                        offset = component.offset,
                        window = component.window,
                        totalCount = component.totalCount,
                    )
                if (target != null && target != lastRequested) {
                    lastRequested = target
                    currentOnAction(
                        UserAction.ListWindowRequested(
                            componentId = component.id,
                            offset = target,
                        ),
                    )
                }
            }
    }
}
