// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/** Everything a rendered screen can ask its owner to do. */
class PresentationScreenActions(
    val onEvent: (surfaceId: String, event: PresentationEvent) -> Unit,
    val onSurfaceActivated: (surfaceId: String) -> Unit,
    val onCameraPermissionDenied: () -> Unit,
    val onDismissOverlay: () -> Unit,
)

/**
 * The screen the app shows for a fully prepared [PresentationState]: the
 * active surface (or the split pair), its context bar and persistent
 * navigation bar, and any open overlay. Pure rendering of Core's
 * commands — no engine, view model, or activity behind it — so the same
 * composable serves the running app and a fixture replay.
 */
@Composable
fun PresentationScreen(
    state: PresentationState,
    profile: PresentationProfile,
    activeSurfaceId: String,
    reducedMotion: Boolean,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    actions: PresentationScreenActions,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    dialogs: @Composable () -> Unit = {},
) {
    // ContextCommandBar and PresentationOverlay sit beside, not below,
    // PresentationSurface — which provides the token for its own
    // subtree — so they need the active surface's tokens provided
    // again here to size their own touch targets.
    val tokens = state.surfaces[activeSurfaceId]?.tokens ?: DefaultPresentationTokens
    CompositionLocalProvider(LocalPresentationTokens provides tokens) {
        Scaffold(
            modifier =
                modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        val action =
                            shortcutGesture(event.key, event.isCtrlPressed, event.isAltPressed)
                                ?.let { contextualShortcut(state.activeBar, it) }
                                ?: return@onPreviewKeyEvent false
                        actions.onEvent(
                            activeSurfaceId,
                            PresentationEvent.ActionActivated(
                                surfaceId = activeSurfaceId,
                                interactionId = action.interactionId,
                            ),
                        )
                        true
                    },
            snackbarHost = snackbarHost,
            bottomBar = {
                Column {
                    Box(
                        modifier =
                            Modifier
                                .padding(
                                    horizontal =
                                        if (profile.windowClass == WindowClass.Compact) {
                                            0.dp
                                        } else {
                                            24.dp
                                        },
                                    vertical =
                                        if (profile.windowClass == WindowClass.Compact) {
                                            0.dp
                                        } else {
                                            12.dp
                                        },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContextCommandBar(
                            surfaceId = activeSurfaceId,
                            bar = state.activeBar,
                            windowClass = profile.windowClass,
                            onEvent = { actions.onEvent(activeSurfaceId, it) },
                        )
                    }
                    PersistentNavigationBar(
                        surfaceId = activeSurfaceId,
                        navigation = state.activeNavigation,
                        onEvent = { actions.onEvent(activeSurfaceId, it) },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                if (profile.paneLayout == PaneLayout.Split) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        state.surfaces[profile.primarySurface]?.let { surface ->
                            SurfaceHost(
                                surface = surface,
                                active = surface.surfaceId == activeSurfaceId,
                                actions = actions,
                                focusedBindingId = focusedBindingId,
                                onFocusedBinding = onFocusedBinding,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        profile.detailSurface
                            ?.let(state.surfaces::get)
                            ?.let { surface ->
                                SurfaceHost(
                                    surface = surface,
                                    active = surface.surfaceId == activeSurfaceId,
                                    actions = actions,
                                    focusedBindingId = focusedBindingId,
                                    onFocusedBinding = onFocusedBinding,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                    }
                } else {
                    state.surfaces[activeSurfaceId]?.let { surface ->
                        SurfaceHost(
                            surface = surface,
                            active = true,
                            actions = actions,
                            focusedBindingId = focusedBindingId,
                            onFocusedBinding = onFocusedBinding,
                        )
                    }
                }

                state.activeOverlay?.let { overlay ->
                    PresentationOverlay(
                        overlay = overlay,
                        windowClass = profile.windowClass,
                        reducedMotion = reducedMotion,
                        onAction = { actions.onEvent(overlay.surfaceId, it) },
                        onDismiss = actions.onDismissOverlay,
                    )
                }

                dialogs()
            }
        }
    }
}

private fun shortcutGesture(
    key: Key,
    ctrl: Boolean,
    alt: Boolean,
): ShortcutGesture? =
    when {
        key == Key.Escape -> ShortcutGesture.Back
        ctrl && key == Key.K -> ShortcutGesture.Navigation
        ctrl && key == Key.Enter -> ShortcutGesture.Primary
        alt && key == Key.DirectionDown -> ShortcutGesture.Secondary
        ctrl && key == Key.Z -> ShortcutGesture.Undo
        else -> null
    }

@Composable
private fun SurfaceHost(
    surface: SurfaceSpec,
    active: Boolean,
    actions: PresentationScreenActions,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PresentationSurface(
        surface = surface,
        active = active,
        onActivate = { actions.onSurfaceActivated(surface.surfaceId) },
        onEvent = { actions.onEvent(surface.surfaceId, it) },
        onCameraPermissionDenied = actions.onCameraPermissionDenied,
        focusedBindingId = focusedBindingId,
        onFocusedBinding = onFocusedBinding,
        modifier = modifier,
    )
}
