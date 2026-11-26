// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import app.vauchi.contract.Command
import kotlinx.coroutines.delay

internal class DisplayEffectPresentation(
    val snackbar: SnackbarHostState,
    val alert: DisplayEffect?,
    val celebrating: Boolean,
)

@Composable
internal fun rememberDisplayEffects(
    owner: AndroidDisplayExecutor,
    effects: List<DisplayEffect>,
    reducedMotion: Boolean,
): DisplayEffectPresentation {
    val snackbar = remember(owner) { SnackbarHostState() }
    val toast = effects.firstOrNull { it.command is Command.ShowToast }
    val alert = effects.firstOrNull { it.command is Command.PresentAlert }
    val celebration = effects.firstOrNull { it.command is Command.Celebrate }
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    var celebrating by remember(owner) { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(owner, toast?.token) {
        toast?.let {
            snackbar.showSnackbar((it.command as Command.ShowToast).toast.message)
            owner.consumeEffect(it.token)
        }
    }
    LaunchedEffect(owner, celebration?.token) {
        celebration?.let {
            val command = it.command as Command.Celebrate
            celebrationHapticFeedback(command.haptic.wireValue)?.let(view::performHapticFeedback)
            try {
                if (celebrationAnimationPlays(command.animation.wireValue, currentReducedMotion)) {
                    celebrating = true
                    delay(CELEBRATION_BEAT_MILLIS)
                }
            } finally {
                celebrating = false
            }
            // Clearing the token before the beat would cancel its cleanup (#276).
            owner.consumeEffect(it.token)
        }
    }
    return DisplayEffectPresentation(snackbar, alert, celebrating && !reducedMotion)
}

@Composable
internal fun DisplayAlert(
    effect: DisplayEffect?,
    owner: AndroidDisplayExecutor,
) {
    val current = effect ?: return
    val alert = (current.command as Command.PresentAlert).alert
    AlertDialog(
        onDismissRequest = { owner.consumeEffect(current.token) },
        title = { Text(alert.title) },
        text = { Text(alert.message) },
        confirmButton = {
            TextButton(onClick = { owner.consumeEffect(current.token) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
