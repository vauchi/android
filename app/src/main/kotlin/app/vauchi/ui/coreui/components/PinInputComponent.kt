// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core PinInput component as a PIN entry field
 * with optional masking and validation error display.
 */
@Composable
fun PinInputComponent(
    componentId: String,
    label: String,
    length: Int,
    masked: Boolean,
    validationError: String?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // F2-NEW-6: hold the entered buffer locally so the field reflects
    // the user's progress (masked dots when `masked = true`, plain
    // digits otherwise) and forwards each keystroke to core. The
    // previous shape pinned `value = ""` which left the field
    // visually blank — users couldn't tell how many digits they had
    // entered, and rapid input via `adb shell input text` (used by
    // the device-test campaign) silently dropped digits when key
    // events arrived faster than recomposition could surface them.
    // Core stays authoritative for engine state because we forward
    // the full buffer on every change.
    var buffer by remember(componentId) { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = buffer,
            onValueChange = { newValue ->
                val bounded = newValue.take(length)
                buffer = bounded
                onAction(UserAction.TextChanged(componentId = componentId, value = bounded))
            },
            label = { Text(label) },
            isError = validationError != null,
            supportingText =
                validationError?.let {
                    {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            singleLine = true,
            visualTransformation =
                if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
