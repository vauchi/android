// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vauchi.ui.theme.MonospaceFontFamily
import kotlin.math.roundToInt

@Composable
internal fun PresentationNodeRenderer(
    surfaceId: String,
    node: PresentationNode,
    onEvent: (PresentationEvent) -> Unit,
    onCameraPermissionDenied: () -> Unit,
    focusedBindingId: String? = null,
    onFocusedBinding: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    // A node rendered beside a weighted sibling must not claim the full
    // width: a toggle that does swallows the row and neither is measured.
    fillWidth: Boolean = true,
) {
    val tokens = LocalPresentationTokens.current
    when (node) {
        is PresentationNode.Text -> {
            val role = textRoleStyle(node.style)
            val base =
                when (role.typography) {
                    TextTypography.TitleLarge -> MaterialTheme.typography.titleLarge
                    TextTypography.BodyLarge -> MaterialTheme.typography.bodyLarge
                    TextTypography.BodySmall -> MaterialTheme.typography.bodySmall
                }
            Text(
                text = node.content,
                style = if (role.monospaced) base.copy(fontFamily = MonospaceFontFamily) else base,
                color =
                    if (role.muted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.Unspecified
                    },
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = node.accessibility.label
                            if (node.style == TextRole.Heading) {
                                heading()
                            }
                        },
            )
        }

        is PresentationNode.Input -> {
            val inputState = remember(node.bindingId) { PresentationInputState(node.value) }
            val focusRequester = remember(node.bindingId) { FocusRequester() }
            val wasFocused = remember(node.bindingId) { mutableStateOf(false) }
            LaunchedEffect(focusedBindingId, node.bindingId) {
                if (shouldRestoreFocus(focusedBindingId, node.bindingId)) {
                    focusRequester.requestFocus()
                }
            }
            OutlinedTextField(
                value = inputState.value,
                onValueChange = {
                    val acceptedValue = inputState.accept(it, node.maxLength)
                    onEvent(
                        PresentationEvent.textValue(
                            surfaceId,
                            node.bindingId,
                            acceptedValue,
                        ),
                    )
                },
                enabled = node.enabled,
                label = { Text(node.label) },
                placeholder = node.placeholder?.let { { Text(it) } },
                isError = node.validationError != null,
                supportingText = node.validationError?.let { { Text(it) } },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = keyboardType(node.inputKind),
                        imeAction = if (node.inputKind == "multiline") ImeAction.Default else ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            onEvent(PresentationEvent.InputSubmitted(surfaceId, node.bindingId))
                        },
                    ),
                singleLine = node.inputKind != "multiline",
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            onFocusedBinding(node.bindingId, it.isFocused)
                            // Report only the transition out of the field,
                            // and only once it has actually held focus —
                            // Compose emits unfocused on first composition
                            // too, which would look like the user leaving a
                            // field they never entered.
                            if (!it.isFocused && wasFocused.value) {
                                onEvent(
                                    PresentationEvent.InputFocusEnded(surfaceId, node.bindingId),
                                )
                            }
                            wasFocused.value = it.isFocused
                        }.semantics {
                            contentDescription = node.accessibility.label
                        },
            )
        }

        is PresentationNode.Toggle -> {
            // The row owns the toggle and the Switch is decoration
            // (`onCheckedChange = null`). Giving both an action published two
            // actionable nodes, and the Switch's carried no label — a screen
            // reader then announced a switch without saying which group it
            // governs, on the control that decides who sees a card field.
            // `toggleable` also supplies ToggleableState, so the on/off state
            // reaches assistive tech instead of only the visual knob position.
            Row(
                modifier =
                    modifier
                        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                        .heightIn(min = minimumTouchTarget(tokens))
                        .toggleable(
                            value = node.value,
                            enabled = node.enabled,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                onEvent(
                                    PresentationEvent.booleanValue(
                                        surfaceId,
                                        node.bindingId,
                                        checked,
                                    ),
                                )
                            },
                        ).semantics {
                            contentDescription = node.accessibility.label
                        },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Only the full-width form spreads label and switch apart;
                // beside a title the row already owns the label, and a
                // weighted empty Text would push the switch off the row.
                Text(
                    node.label,
                    modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
                )
                Switch(
                    checked = node.value,
                    onCheckedChange = null,
                    enabled = node.enabled,
                )
            }
        }

        is PresentationNode.Choice -> {
            ChoiceNode(surfaceId, node, onEvent, modifier)
        }

        is PresentationNode.Group -> {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = node.accessibility.label
                        },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.label?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                if (node.horizontal) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        node.children.forEach {
                            PresentationNodeRenderer(
                                surfaceId,
                                it,
                                onEvent,
                                onCameraPermissionDenied,
                                focusedBindingId,
                                onFocusedBinding,
                            )
                        }
                    }
                } else {
                    node.children.forEach {
                        PresentationNodeRenderer(
                            surfaceId,
                            it,
                            onEvent,
                            onCameraPermissionDenied,
                            focusedBindingId,
                            onFocusedBinding,
                        )
                    }
                }
            }
        }

        is PresentationNode.ListNode -> {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = node.accessibility.label
                        },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.label?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                node.rows.forEach { row ->
                    PresentationListRow(
                        surfaceId,
                        row,
                        onEvent,
                        onCameraPermissionDenied,
                        focusedBindingId,
                        onFocusedBinding,
                    )
                }
            }
        }

        is PresentationNode.Image -> {
            val bitmap =
                remember(node.data) {
                    node.data
                        ?.map(Int::toByte)
                        ?.toByteArray()
                        ?.let {
                            BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                }
            val fallback = node.fallbackText
            // Nothing to show shows nothing. Rendering the Surface anyway
            // left an empty full-width band carrying the avatar's
            // accessibility label.
            if (bitmap != null || !fallback.isNullOrEmpty()) {
                val activation = node.activation
                Surface(
                    modifier =
                        modifier
                            // Square whenever the result is an avatar: a
                            // circle clipped from a `fillMaxWidth` box is a
                            // stadium as wide as the screen, which is what
                            // the initials used to sit in. Diameter is the
                            // surface's minimumTargetSize token — the same
                            // floor macOS frames its fallback avatar at —
                            // rather than a shell-picked constant.
                            .then(
                                if (node.circular || bitmap == null) {
                                    Modifier.size(minimumTouchTarget(tokens))
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                            ).then(
                                if (activation != null) {
                                    Modifier.clickable(enabled = activation.enabled) {
                                        onEvent(actionEvent(surfaceId, activation))
                                    }
                                } else {
                                    Modifier
                                },
                            ).semantics {
                                contentDescription = node.accessibility.label
                            },
                    shape = if (node.circular) CircleShape else MaterialTheme.shapes.medium,
                    // Matches `RowAvatar` below, which has always drawn the
                    // initials on a filled ground.
                    color =
                        if (bitmap == null) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = if (node.circular) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                fallback.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
        }

        is PresentationNode.Status -> {
            val activation = node.activation
            Surface(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .then(
                            if (activation != null) {
                                Modifier.clickable(enabled = activation.enabled) {
                                    onEvent(actionEvent(surfaceId, activation))
                                }
                            } else {
                                Modifier
                            },
                        ).semantics {
                            contentDescription = node.accessibility.label
                        },
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    node.iconToken?.let(::statusIcon)?.let { glyph ->
                        Icon(
                            imageVector = glyph,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(node.title, style = MaterialTheme.typography.titleMedium)
                        node.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    node.badge?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        is PresentationNode.Qr -> {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = node.accessibility.label
                        },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                node.label?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                if (node.capture) {
                    QrScanner(
                        accessibilityLabel = node.accessibility.label,
                        onScanned = {
                            onEvent(
                                PresentationEvent.textValue(
                                    surfaceId,
                                    node.id,
                                    it,
                                ),
                            )
                        },
                        onPermissionDenied = onCameraPermissionDenied,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    node.payloads.firstOrNull()?.let {
                        QrDisplay(
                            data = it,
                            accessibilityLabel = node.accessibility.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        is PresentationNode.Confirmation -> {
            Surface(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = node.accessibility.label
                        },
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(node.warning)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = {
                                onEvent(actionEvent(surfaceId, node.cancel))
                            },
                            enabled = node.cancel.enabled,
                        ) {
                            Text(node.cancel.label)
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                onEvent(actionEvent(surfaceId, node.confirm))
                            },
                            enabled = node.confirm.enabled,
                        ) {
                            Text(node.confirm.label)
                        }
                    }
                }
            }
        }

        is PresentationNode.Slider -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Text(node.label)
                Slider(
                    value = node.value.toFloat(),
                    onValueChange = {
                        val raw = it.toDouble()
                        val value =
                            node.step?.takeIf { step -> step > 0.0 }?.let { step ->
                                (raw / step).roundToInt() * step
                            } ?: raw
                        onEvent(
                            PresentationEvent.numberValue(
                                surfaceId,
                                node.bindingId,
                                value,
                            ),
                        )
                    },
                    valueRange = node.minimum.toFloat()..node.maximum.toFloat(),
                    modifier =
                        Modifier.semantics {
                            contentDescription = node.accessibility.label
                        },
                )
            }
        }

        is PresentationNode.Progress -> {
            Column(modifier = modifier.fillMaxWidth()) {
                node.label?.let { Text(it) }
                if (node.value == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { node.value.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        PresentationNode.Divider -> {
            HorizontalDivider(modifier)
        }
    }
}

/**
 * Two or three options are a mode switch (Perspective, Members /
 * Visibility) and read as segments the design canvas puts one tap away;
 * longer lists (Theme has fifteen) stay behind a dropdown, where a row
 * of segments would not fit. Both shapes emit the same choice event.
 */
private const val MAX_SEGMENTED_OPTIONS = 3

@Composable
private fun ChoiceNode(
    surfaceId: String,
    node: PresentationNode.Choice,
    onEvent: (PresentationEvent) -> Unit,
    modifier: Modifier,
) {
    val select: (ChoiceOption) -> Unit = { option ->
        onEvent(PresentationEvent.choiceValue(surfaceId, node.bindingId, option.id))
    }
    if (node.options.size in 2..MAX_SEGMENTED_OPTIONS) {
        SegmentedChoice(node, select, modifier)
    } else {
        DropdownChoice(node, select, modifier)
    }
}

@Composable
private fun SegmentedChoice(
    node: PresentationNode.Choice,
    select: (ChoiceOption) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(node.label, style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = node.accessibility.label
                    },
        ) {
            node.options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.id == node.selected,
                    onClick = { select(option) },
                    enabled = node.enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = node.options.size),
                ) {
                    Text(option.label)
                }
            }
        }
    }
}

@Composable
private fun DropdownChoice(
    node: PresentationNode.Choice,
    select: (ChoiceOption) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = node.options.firstOrNull { it.id == node.selected }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = node.enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = node.accessibility.label
                    },
        ) {
            Text(selected?.label ?: node.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            node.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        RadioButton(
                            selected = option.id == node.selected,
                            onClick = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        select(option)
                    },
                )
            }
        }
    }
}

/**
 * The avatar Core attaches to a row.
 *
 * `imageData` and `fallbackText` were decoded and then dropped, so contact
 * rows rendered as bare text on Android while iOS showed the initials
 * circle from the same commands.
 *
 * Kept at the Material list-avatar size rather than the surface's
 * `minimumTargetSize` token: the token now reaches the standalone avatar
 * and the row's own touch target below, but a row's avatar is decoration
 * beside a much larger tappable row, not itself the target.
 */
@Composable
private fun RowAvatar(row: PresentationRow) {
    val bitmap =
        remember(row.imageData) {
            row.imageData
                ?.map(Int::toByte)
                ?.toByteArray()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    val fallback = row.fallbackText
    if (bitmap == null && fallback.isNullOrEmpty()) return

    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                // The row already carries the Core-supplied label; a second
                // description here would read the row twice.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                fallback.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PresentationListRow(
    surfaceId: String,
    row: PresentationRow,
    onEvent: (PresentationEvent) -> Unit,
    onCameraPermissionDenied: () -> Unit,
    focusedBindingId: String?,
    onFocusedBinding: (String, Boolean) -> Unit,
) {
    val tokens = LocalPresentationTokens.current
    var expanded by remember { mutableStateOf(false) }
    val activation = row.activation
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minimumTouchTarget(tokens))
                .then(
                    if (activation != null) {
                        Modifier.clickable(enabled = row.enabled && activation.enabled) {
                            onEvent(actionEvent(surfaceId, activation))
                        }
                    } else {
                        Modifier
                    },
                ).then(
                    // A row that delegates its name to a control must not
                    // also carry it: the two then read back as separate
                    // stops with the same words, only the second operable.
                    if (row.controls.isEmpty() || activation != null) {
                        Modifier.semantics {
                            contentDescription = row.accessibility.label
                        }
                    } else {
                        Modifier
                    },
                ),
        shape = MaterialTheme.shapes.medium,
        color =
            if (row.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowAvatar(row)
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            // Beside the text column, not inside it: the row title names the
            // setting, so Core sends the control unlabelled and a control
            // stacked under the text reads as belonging to nothing.
            row.controls.forEach {
                PresentationNodeRenderer(
                    surfaceId,
                    it,
                    onEvent,
                    onCameraPermissionDenied,
                    focusedBindingId,
                    onFocusedBinding,
                    fillWidth = false,
                )
            }
            if (row.secondaryActions.isNotEmpty()) {
                Box {
                    IconButton(
                        onClick = { expanded = true },
                        enabled = row.enabled,
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription =
                                row.secondaryActions
                                    .first()
                                    .accessibilityLabel,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        row.secondaryActions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                enabled = action.enabled,
                                onClick = {
                                    expanded = false
                                    onEvent(actionEvent(surfaceId, action))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun actionEvent(
    surfaceId: String,
    action: ActionSpec,
): PresentationEvent =
    PresentationEvent.ActionActivated(
        surfaceId = surfaceId,
        interactionId = action.interactionId,
    )

private fun keyboardType(inputKind: String): KeyboardType =
    when (inputKind) {
        "email" -> KeyboardType.Email
        "phone" -> KeyboardType.Phone
        "number" -> KeyboardType.Number
        "password" -> KeyboardType.Password
        else -> KeyboardType.Text
    }
