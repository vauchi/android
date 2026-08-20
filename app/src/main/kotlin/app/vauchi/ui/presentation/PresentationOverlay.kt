// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class OverlayTransitionIdentity {
    NavigationReveal,
    NavigationReduced,
    ActionReveal,
    ActionReduced,
}

internal fun overlayTransitionIdentity(
    kind: OverlayKind,
    reducedMotion: Boolean,
): OverlayTransitionIdentity =
    when {
        kind == OverlayKind.Navigation && reducedMotion -> {
            OverlayTransitionIdentity.NavigationReduced
        }

        kind == OverlayKind.Navigation -> {
            OverlayTransitionIdentity.NavigationReveal
        }

        reducedMotion -> {
            OverlayTransitionIdentity.ActionReduced
        }

        else -> {
            OverlayTransitionIdentity.ActionReveal
        }
    }

@Composable
fun PresentationOverlay(
    overlay: RevisionedOverlay,
    windowClass: WindowClass,
    reducedMotion: Boolean,
    onAction: (PresentationEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val transitionIdentity =
        overlayTransitionIdentity(overlay.overlay.kind, reducedMotion)
    val navigation =
        transitionIdentity == OverlayTransitionIdentity.NavigationReveal ||
            transitionIdentity == OverlayTransitionIdentity.NavigationReduced
    val enter =
        if (navigation) {
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec =
                    if (reducedMotion) {
                        snap()
                    } else {
                        androidx.compose.animation.core
                            .tween(240)
                    },
            ) +
                fadeIn(
                    animationSpec =
                        if (reducedMotion) {
                            snap()
                        } else {
                            androidx.compose.animation.core
                                .tween(160)
                        },
                )
        } else {
            slideInVertically(
                initialOffsetY = { it },
                animationSpec =
                    if (reducedMotion) {
                        snap()
                    } else {
                        androidx.compose.animation.core
                            .tween(220)
                    },
            ) +
                scaleIn(
                    initialScale = if (reducedMotion) 1f else 0.96f,
                    animationSpec =
                        if (reducedMotion) {
                            snap()
                        } else {
                            androidx.compose.animation.core
                                .tween(220)
                        },
                )
        }
    val exit =
        if (navigation) {
            slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec =
                    if (reducedMotion) {
                        snap()
                    } else {
                        androidx.compose.animation.core
                            .tween(180)
                    },
            ) +
                fadeOut(
                    animationSpec =
                        if (reducedMotion) {
                            snap()
                        } else {
                            androidx.compose.animation.core
                                .tween(120)
                        },
                )
        } else {
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec =
                    if (reducedMotion) {
                        snap()
                    } else {
                        androidx.compose.animation.core
                            .tween(180)
                    },
            ) +
                scaleOut(
                    targetScale = if (reducedMotion) 1f else 0.96f,
                    animationSpec =
                        if (reducedMotion) {
                            snap()
                        } else {
                            androidx.compose.animation.core
                                .tween(180)
                        },
                )
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        contentAlignment =
            if (navigation) {
                Alignment.CenterStart
            } else if (windowClass == WindowClass.Compact) {
                Alignment.BottomCenter
            } else {
                Alignment.BottomEnd
            },
    ) {
        AnimatedVisibility(
            visible = true,
            enter = enter,
            exit = exit,
        ) {
            OverlayPanel(
                overlay = overlay,
                navigation = navigation,
                compact = windowClass == WindowClass.Compact,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun OverlayPanel(
    overlay: RevisionedOverlay,
    navigation: Boolean,
    compact: Boolean,
    onAction: (PresentationEvent) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .then(
                    if (navigation) {
                        Modifier.fillMaxHeight().widthIn(max = 360.dp)
                    } else if (compact) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.widthIn(min = 320.dp, max = 420.dp)
                    },
                ).clickable(enabled = false) {},
        shape =
            if (navigation) {
                MaterialTheme.shapes.extraLarge
            } else {
                MaterialTheme.shapes.extraLarge
            },
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            overlay.overlay.title?.let {
                Text(it, style = MaterialTheme.typography.titleLarge)
            }
            // Core decides how many items an overlay carries, so the panel
            // cannot assume they fit the viewport — without this the last
            // destinations are clipped away with no way to reach them.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                overlay.overlay.items.forEach { action ->
                    Button(
                        onClick = {
                            onAction(
                                PresentationEvent.ActionActivated(
                                    overlay.surfaceId,
                                    action.interactionId,
                                ),
                            )
                        },
                        enabled = action.enabled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = action.accessibilityLabel
                                },
                    ) {
                        Row(horizontalArrangement = Arrangement.Center) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}
