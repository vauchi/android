// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import app.vauchi.display.CELEBRATION_BEAT_MILLIS
import app.vauchi.display.celebrationAnimationPlays
import app.vauchi.display.celebrationHapticFeedback
import app.vauchi.ui.coreui.CelebrateRequest
import kotlinx.coroutines.delay

@Composable
internal fun rememberCelebrating(
    request: CelebrateRequest?,
    reducedMotion: Boolean,
    consumeRequest: () -> Unit,
): Boolean {
    var celebrating by remember { mutableStateOf(false) }
    val hostView = LocalView.current
    LaunchedEffect(request) {
        val celebration = request ?: return@LaunchedEffect
        celebrationHapticFeedback(celebration.haptic)?.let { hostView.performHapticFeedback(it) }
        if (celebrationAnimationPlays(celebration.animation, reducedMotion)) {
            celebrating = true
            delay(CELEBRATION_BEAT_MILLIS)
            celebrating = false
        }
        // Clearing the effect's key before the delay cancels its cleanup (#276).
        consumeRequest()
    }
    return celebrating
}
