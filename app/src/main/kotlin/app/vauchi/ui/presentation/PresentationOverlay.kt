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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.theme.LocalStatusColors

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

    // The scrim is a sibling of the panel, not its parent. As a parent its
    // `clickable` received every gesture the panel did not consume, and
    // `clickable(enabled = false)` consumes nothing — so a drag meant for the
    // list read as a tap on the scrim and dismissed the overlay. On a small
    // screen that left the destinations below the fold unreachable: verified
    // on a Galaxy S7, where Backup clipped mid-row and Privacy, Support,
    // Help, Activity Log, Tags and Places were absent from the tree entirely.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment =
            if (navigation) {
                Alignment.CenterStart
            } else if (windowClass == WindowClass.Compact) {
                Alignment.BottomCenter
            } else {
                Alignment.BottomEnd
            },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .clickable(onClick = onDismiss),
        )
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
                        // Not `fillMaxHeight`: the inner column already
                        // scrolls, so it grows to the viewport when the menu
                        // needs it and stops at its content when it does not.
                        // Forcing full height made a one-item menu cover the
                        // scrim, so tapping beside it could not dismiss.
                        Modifier.widthIn(max = 360.dp)
                    } else if (compact) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.widthIn(min = 320.dp, max = 420.dp)
                    },
                ),
        shape = MaterialTheme.shapes.extraLarge,
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
                    OverlayActionButton(surfaceId = overlay.surfaceId, action = action, onAction = onAction)
                }
            }
        }
    }
}

/**
 * One overlay action row, styled from [ActionSpec.tone] via [toneColors] so
 * a serious action (verify a fingerprint, schedule a deletion) reads as
 * consequential without borrowing destructive's filled error red.
 */
@Composable
private fun OverlayActionButton(
    surfaceId: String,
    action: ActionSpec,
    onAction: (PresentationEvent) -> Unit,
) {
    val style = toneColors(action.tone, MaterialTheme.colorScheme, LocalStatusColors.current)
    val modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = minimumTouchTarget(LocalPresentationTokens.current))
            .semantics {
                contentDescription = action.accessibilityLabel
            }
    val onClick = {
        onAction(
            PresentationEvent.ActionActivated(surfaceId, action.interactionId),
        )
    }
    val content: @Composable RowScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Start-aligned, not centred: a centred row puts each glyph at
            // an x that depends on its label's length, so the icons stop
            // forming a column the eye can scan — which is the whole point
            // of showing them.
            action.iconToken?.let { token ->
                Icon(
                    imageVector = navigationIcon(token),
                    // The button already carries `accessibilityLabel` and
                    // the label reads beside it; describing the icon too
                    // makes TalkBack announce the destination twice.
                    contentDescription = null,
                )
            }
            Text(action.label)
        }
    }
    when (style.emphasis) {
        ActionToneEmphasis.Filled -> {
            Button(
                onClick = onClick,
                enabled = action.enabled,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = style.accent,
                        contentColor = contentColorFor(style.accent),
                    ),
                modifier = modifier,
                content = content,
            )
        }

        ActionToneEmphasis.Outlined -> {
            OutlinedButton(
                onClick = onClick,
                enabled = action.enabled,
                border = BorderStroke(1.dp, style.accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = style.accent),
                modifier = modifier,
                content = content,
            )
        }
    }
}
