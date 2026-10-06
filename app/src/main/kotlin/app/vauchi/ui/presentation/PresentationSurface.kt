// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
internal fun PresentationSurface(
    surface: SurfaceSpec,
    active: Boolean,
    bar: ContextBar?,
    navigationShown: Boolean,
    onActivate: () -> Unit,
    onEvent: (PresentationEvent) -> Unit,
    onCameraPermissionDenied: () -> Unit,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val barModel = ContextBarModel(bar, navigationShown)
    Surface(
        modifier =
            modifier
                .fillMaxSize()
                // Any tap anywhere on the surface takes focus out of a
                // field. Compose reports focus loss only when focus moves
                // to another focusable, so tapping a toggle row or empty
                // space otherwise leaves a field focused and Core never
                // learns the user moved on — which is what makes
                // `InputFocusEnded` fire at all.
                //
                // Watched on the Initial pass because children consume
                // their taps: a gesture detector here would never see a
                // press on a chip or a button.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focusManager.clearFocus()
                    }
                }.clickable(onClick = onActivate)
                .semantics {
                    contentDescription = surface.accessibilityLabel
                },
        border =
            if (active) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
            } else {
                null
            },
    ) {
        CompositionLocalProvider(LocalPresentationTokens provides surface.tokens) {
            Column(modifier = Modifier.fillMaxSize()) {
                SurfaceTitleRow(
                    surfaceId = surface.surfaceId,
                    title = surface.title,
                    subtitle = surface.subtitle,
                    bar = bar,
                    model = barModel,
                    onEvent = onEvent,
                )
                if (surface.layout == SURFACE_LAYOUT_FIXED) {
                    FixedSurfaceContent(
                        surface = surface,
                        onEvent = onEvent,
                        onCameraPermissionDenied = onCameraPermissionDenied,
                        focusedBindingId = focusedBindingId,
                        onFocusedBinding = onFocusedBinding,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(
                                    horizontal = surface.tokens.spacingLarge.dp,
                                    vertical = surface.tokens.spacingMedium.dp,
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(surface.tokens.spacingMedium.dp),
                    ) {
                        items(
                            items = surface.nodes.withIndex().toList(),
                            key = { (index, node) -> node.stableKey(index) },
                        ) { (_, node) ->
                            PresentationNodeRenderer(
                                surfaceId = surface.surfaceId,
                                node = node,
                                onEvent = onEvent,
                                onCameraPermissionDenied = onCameraPermissionDenied,
                                focusedBindingId = focusedBindingId,
                                onFocusedBinding = onFocusedBinding,
                            )
                        }
                    }
                }
                // Only when Core sends one: a fixed-layout surface keeps it
                // in view below the weighted content above, a scrolling
                // one pins it under the LazyColumn rather than letting it
                // scroll away with the list (vauchi/private#534).
                if (barModel.hasPrimary) {
                    bar?.primary?.let {
                        PrimaryActionButton(
                            surfaceId = surface.surfaceId,
                            action = it,
                            onEvent = onEvent,
                            modifier =
                                Modifier.padding(
                                    horizontal = surface.tokens.spacingLarge.dp,
                                    vertical = surface.tokens.spacingMedium.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The surface's title, with Core's back/navigation launcher leading it and
 * info/actions trailing it — the same context-bar slots the retired row
 * above the tab bar used to draw (vauchi/private#479, #534). An absent
 * slot takes no space; a long title wraps rather than pushing the icons.
 */
@Composable
private fun SurfaceTitleRow(
    surfaceId: String,
    title: String,
    subtitle: String?,
    bar: ContextBar?,
    model: ContextBarModel,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPresentationTokens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = tokens.spacingLarge.dp,
                    vertical = tokens.spacingSmall.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingSmall.dp),
    ) {
        model.leadingSlots.forEach { slot ->
            val action = if (slot == ContextBarSlot.Back) bar?.back else bar?.navigation
            action?.let {
                ContextBarIconButton(
                    action = it,
                    slot = slot,
                    onClick = {
                        onEvent(
                            if (slot == ContextBarSlot.Back) {
                                PresentationEvent.BackRequested(surfaceId)
                            } else {
                                PresentationEvent.ActionActivated(surfaceId, it.interactionId)
                            },
                        )
                    },
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        model.trailingSlots.forEach { slot ->
            val action = if (slot == ContextBarSlot.Info) bar?.info else bar?.secondary
            action?.let {
                ContextBarIconButton(
                    action = it,
                    slot = slot,
                    onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, it.interactionId)) },
                )
            }
        }
    }
}

/** Core's primary slot as a full-width button under the surface content. */
@Composable
private fun PrimaryActionButton(
    surfaceId: String,
    action: ActionSpec,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onEvent(PresentationEvent.ActionActivated(surfaceId, action.interactionId)) },
        enabled = action.enabled,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("contextbar.primary")
                .semantics { contentDescription = action.accessibilityLabel },
    ) {
        if (action.shortcut == "undo") {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(action.label)
    }
}

internal const val SURFACE_LAYOUT_FIXED = "fixed"

/**
 * True for a capture QR rendered inside a `Fixed` surface, where it takes
 * the height left over instead of a fixed box.
 */
internal val LocalFillsRemainingHeight = compositionLocalOf { false }

/**
 * Core marks a surface `Fixed` when its content must stay on one screen
 * (the exchange QR and the camera that reads the peer's). A scrolling list
 * would push the camera preview below the fold, so the preview takes the
 * height the other nodes leave instead.
 */
@Composable
private fun FixedSurfaceContent(
    surface: SurfaceSpec,
    onEvent: (PresentationEvent) -> Unit,
    onCameraPermissionDenied: () -> Unit,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = surface.tokens.spacingLarge.dp,
                    vertical = surface.tokens.spacingMedium.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(surface.tokens.spacingMedium.dp),
    ) {
        surface.nodes.forEach { node ->
            val fillsRemaining = node.holdsCamera
            val share = node.fixedSurfaceShare
            androidx.compose.foundation.layout.Box(
                modifier =
                    if (share != null) {
                        Modifier
                            .weight(share.weight, fill = share.fill)
                            .fillMaxWidth()
                            .zIndex(if (share.fill) 0f else 1f)
                    } else {
                        Modifier
                    },
            ) {
                CompositionLocalProvider(LocalFillsRemainingHeight provides fillsRemaining) {
                    PresentationNodeRenderer(
                        surfaceId = surface.surfaceId,
                        node = node,
                        onEvent = onEvent,
                        onCameraPermissionDenied = onCameraPermissionDenied,
                        focusedBindingId = focusedBindingId,
                        onFocusedBinding = onFocusedBinding,
                    )
                }
            }
        }
    }
}

private fun PresentationNode.stableKey(index: Int): String =
    when (this) {
        is PresentationNode.Text -> id
        is PresentationNode.Input -> bindingId
        is PresentationNode.Toggle -> bindingId
        is PresentationNode.Choice -> bindingId
        is PresentationNode.Group -> id
        is PresentationNode.ListNode -> id
        is PresentationNode.Image -> id
        is PresentationNode.Status -> id
        is PresentationNode.Qr -> id
        is PresentationNode.Confirmation -> id
        is PresentationNode.Slider -> bindingId
        is PresentationNode.Progress -> label
        PresentationNode.Divider -> null
    } ?: "node:$index"
