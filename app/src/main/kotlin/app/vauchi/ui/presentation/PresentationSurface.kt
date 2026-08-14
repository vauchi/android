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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PresentationSurface(
    surface: SurfaceSpec,
    active: Boolean,
    onActivate: () -> Unit,
    onEvent: (PresentationEvent) -> Unit,
    onCameraPermissionDenied: () -> Unit,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
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
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = surface.tokens.spacingLarge.dp,
                        vertical = surface.tokens.spacingMedium.dp,
                    ),
            verticalArrangement =
                Arrangement.spacedBy(surface.tokens.spacingMedium.dp),
        ) {
            item(key = "${surface.surfaceId}:header") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        surface.title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    surface.subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
