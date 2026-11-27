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
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.vauchi.contract.ContractJson
import app.vauchi.contract.ContractJsonException
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
 * Supplying [previewHost] replaces legacy scanning with the prepared neutral
 * viewport; hardware owns capture and reports its events independently.
 */
@Composable
fun DisplayContent(
    owner: AndroidDisplayExecutor,
    media: PresentationMedia,
    modifier: Modifier = Modifier,
    previewHost: PreviewSurfaceHost? = null,
) {
    key(owner) { OwnedDisplayContent(owner, media, modifier, previewHost) }
}

@Composable
private fun OwnedDisplayContent(
    owner: AndroidDisplayExecutor,
    media: PresentationMedia,
    modifier: Modifier,
    previewHost: PreviewSurfaceHost?,
) {
    val snapshot by owner.state.collectAsState()
    val state = snapshot.presentation
    val nativePreviewHost =
        remember(owner, previewHost) {
            previewHost?.let { host ->
                ReportingPreviewSurfaceHost(host, owner)
            }
        }
    val presentationMedia =
        remember(owner, media, nativePreviewHost, snapshot.previewId) {
            if (nativePreviewHost == null) media else NativePreviewMedia(snapshot.previewId, nativePreviewHost, media)
        }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val darkAppearance = isSystemInDarkTheme()
    val reducedMotion =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    val effects = rememberDisplayEffects(owner, snapshot.effects, reducedMotion)
    var focusedBindingId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val report: (PresentationEvent) -> Boolean = { event -> decodeInput(owner, event)?.let(owner::report) ?: false }

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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                SnackbarHost(effects.snackbar, Modifier.align(Alignment.BottomCenter))
            }
            DisplayAlert(effects.alert, owner)
            return@BoxWithConstraints
        }
        val actions =
            remember(owner, state.activeOverlay) {
                PresentationScreenActions(
                    onEvent = { target, event ->
                        val input = decodeInput(owner, event)
                        if (input != null && owner.report(Event.SurfaceActivated(target))) owner.report(input)
                    },
                    onSurfaceActivated = { owner.report(Event.SurfaceActivated(it)) },
                    onCameraPermissionDenied = { owner.report(Event.PermissionDenied("camera")) },
                    onDismissOverlay = {
                        state.activeOverlay?.let(owner::dismissOverlay)
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
                media = presentationMedia,
                celebrating = effects.celebrating,
                snackbarHost = { SnackbarHost(effects.snackbar) },
                dialogs = { DisplayAlert(effects.alert, owner) },
            )
        }
    }
}

private fun decodeInput(
    owner: AndroidDisplayExecutor,
    event: PresentationEvent,
): Event? =
    try {
        ContractJson.decodeEvent(event.toJson())
    } catch (_: ContractJsonException) {
        owner.close()
        null
    }
