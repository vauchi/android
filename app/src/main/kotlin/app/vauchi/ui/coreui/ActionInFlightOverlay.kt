// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Blocking progress scrim shown while a dispatched [UserAction] is
 * still executing in core. Appears only after [SCRIM_DELAY_MS] so
 * ordinary sub-frame actions never flash; long engine work (backup
 * restore, large imports) gets visible progress and re-submission is
 * physically impossible while it runs
 * (2026-06-11-restore-runs-without-progress-feedback).
 *
 * Must be the last child of each screen host's root Box so it draws
 * over the rendered screen.
 */
@Composable
fun ActionInFlightOverlay(viewModel: CoreAppViewModel) {
    val inFlight by viewModel.actionInFlight.collectAsState()
    var showScrim by remember { mutableStateOf(false) }

    LaunchedEffect(inFlight) {
        if (inFlight) {
            delay(SCRIM_DELAY_MS)
            showScrim = true
        } else {
            showScrim = false
        }
    }

    if (showScrim && inFlight) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* swallow input while core works */ },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

private const val SCRIM_DELAY_MS = 400L
