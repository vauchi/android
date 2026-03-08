// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.vauchi.ui.coreui.UserAction

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
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = "",
            onValueChange = { newValue ->
                val bounded = newValue.take(length)
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
