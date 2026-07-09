// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.InputType
import app.vauchi.ui.coreui.UserAction

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
    val keyboardController = LocalSoftwareKeyboardController.current
    var localValue by remember { mutableStateOf(value) }

    // Sync from core when the authoritative value changes (e.g. screen navigation)
    LaunchedEffect(value) {
        if (value != localValue) {
            localValue = value
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = localValue,
            onValueChange = { newValue ->
                val bounded = if (maxLength != null) newValue.take(maxLength) else newValue
                localValue = bounded
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
            visualTransformation =
                if (inputType == InputType.Password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        when (inputType) {
                            InputType.Text -> KeyboardType.Text
                            InputType.Phone -> KeyboardType.Phone
                            InputType.Email -> KeyboardType.Email
                            InputType.Password -> KeyboardType.Password
                        },
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = { keyboardController?.hide() },
                ),
            modifier = Modifier.fillMaxWidth().testTag(componentId),
        )

        if (maxLength != null) {
            Text(
                text = "${localValue.length}/$maxLength",
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
