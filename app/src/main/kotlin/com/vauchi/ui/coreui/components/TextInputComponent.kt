// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vauchi.ui.coreui.InputType
import com.vauchi.ui.coreui.UserAction

/**
 * Renders a core TextInput component as a Material3 OutlinedTextField
 * with validation error display and keyboard type hints.
 */
@Composable
fun TextInputComponent(
    componentId: String,
    label: String,
    value: String,
    placeholder: String?,
    maxLength: Int?,
    validationError: String?,
    inputType: InputType,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                val bounded = if (maxLength != null) newValue.take(maxLength) else newValue
                onAction(UserAction.TextChanged(componentId = componentId, value = bounded))
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
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
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        when (inputType) {
                            InputType.Text -> KeyboardType.Text
                            InputType.Phone -> KeyboardType.Phone
                            InputType.Email -> KeyboardType.Email
                        },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (maxLength != null) {
            Text(
                text = "${value.length}/$maxLength",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .align(androidx.compose.ui.Alignment.End),
            )
        }
    }
}
