// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.vauchi.util.LocalizationManager

/**
 * App password screen shown after biometric auth when duress mode
 * is configured. Collects a 6-digit PIN and routes it through
 * core.authenticate() which determines Normal vs Duress mode.
 *
 * Visually identical regardless of which PIN is entered — the
 * observer cannot distinguish normal from duress authentication.
 *
 * Note on PIN zeroization: JVM String is immutable — we can't
 * guarantee heap scrubbing. We clear the variable immediately
 * after use and on lifecycle pause. Rust core zeroizes the
 * password after hashing (ZeroizeOnDrop).
 */
@Composable
fun AppPasswordScreen(
    onAuthenticate: (String) -> Unit,
    onCancel: () -> Unit,
    errorMessage: String? = null,
) {
    val context = LocalContext.current
    val localizationManager =
        remember {
            LocalizationManager.getInstance(context)
        }

    var pin by remember { mutableStateOf("") }

    // Clear PIN when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    pin = ""
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = localizationManager.t("resistance.duress.pin_label"),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your app password to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 6 && value.all { it.isDigit() }) {
                    pin = value
                }
            },
            label = {
                Text(
                    localizationManager.t("resistance.duress.pin_label"),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (pin.length == 6) {
                            val entered = pin
                            pin = ""
                            onAuthenticate(entered)
                        }
                    },
                ),
            singleLine = true,
            isError = errorMessage != null,
            supportingText =
                errorMessage?.let { msg ->
                    { Text(msg, color = MaterialTheme.colorScheme.error) }
                },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "App password input, masked"
                    },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val entered = pin
                pin = ""
                onAuthenticate(entered)
            },
            enabled = pin.length == 6,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Unlock with app password"
                    },
        ) {
            Text(localizationManager.t("action.unlock"))
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                pin = ""
                onCancel()
            },
        ) {
            Text(localizationManager.t("action.cancel"))
        }
    }
}
