// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.vauchi.contract.ContractJson
import app.vauchi.contract.Event
import app.vauchi.ui.presentation.PresentationEvent
import app.vauchi.ui.presentation.PresentationMedia
import app.vauchi.ui.presentation.PresentationScreen
import app.vauchi.ui.presentation.PresentationScreenActions
import app.vauchi.ui.presentation.rememberFocusedBinding
import app.vauchi.ui.theme.SurfaceTheme
import kotlin.math.roundToInt

/**
 * Renders the executor owned by the assembly; composition does not close it.
 * Events are admitted to that owner's host inbox, never dispatched to Core here.
 */
@Composable
fun DisplayContent(
    owner: AndroidDisplayExecutor,
    media: PresentationMedia,
    modifier: Modifier = Modifier,
) {
    val snapshot by owner.state.collectAsState()
    val state = snapshot.presentation
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val darkAppearance = isSystemInDarkTheme()
    val reducedMotion =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    var focusedBindingId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val report: (PresentationEvent) -> Boolean = { owner.report(ContractJson.decodeEvent(it.toJson())) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value.roundToInt()
        val height = maxHeight.value.roundToInt()
        val modes = presentationInputModes(configuration)
        LaunchedEffect(owner, width, height, modes, reducedMotion, darkAppearance) {
            report(PresentationEvent.environmentChanged(width, height, modes, reducedMotion, darkAppearance))
        }
        val profile = state.profile
        val surfaceId = state.activeSurfaceId
        BackHandler(enabled = surfaceId != null) {
            surfaceId?.let { owner.report(Event.BackRequested(it)) }
        }
        if (profile == null || surfaceId == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }
        val actions =
            remember(owner, state.activeOverlay) {
                PresentationScreenActions(
                    onEvent = { target, event ->
                        if (owner.report(Event.SurfaceActivated(target))) report(event)
                    },
                    onSurfaceActivated = { owner.report(Event.SurfaceActivated(it)) },
                    onCameraPermissionDenied = { owner.report(Event.PermissionDenied("camera")) },
                    onDismissOverlay = {
                        state.activeOverlay?.let { overlay ->
                            report(PresentationEvent.OverlayDismissed(overlay.surfaceId, overlay.overlay.kind))
                        }
                    },
                )
            }
        SurfaceTheme(state.surfaces[surfaceId]?.tokens?.colors) {
            PresentationScreen(
                state = state,
                profile = profile,
                activeSurfaceId = surfaceId,
                reducedMotion = reducedMotion,
                focusedBindingId = focusedBindingId,
                onFocusedBinding = { binding, focused ->
                    focusedBindingId = rememberFocusedBinding(focusedBindingId, binding, focused)
                },
                actions = actions,
                media = media,
            )
        }
    }
}
