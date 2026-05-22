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
import uniffi.vauchi_platform.passcodeMaxLength
import uniffi.vauchi_platform.passcodeMinLength

/**
 * Passcode policy shared between the unified entry surface and the
 * setup dialogs. Values come from core via the G3 UniFFI helpers
 * (`passcode_min_length()` / `passcode_max_length()`), so a change
 * in policy only needs to land in `vauchi-core` — no per-platform
 * drift. Android, iOS, and linux-qt all call the same two functions.
 */
internal val MIN_PASSCODE_LENGTH: Int = passcodeMinLength().toInt()
internal val MAX_PASSCODE_LENGTH: Int = passcodeMaxLength().toInt()

/**
 * Unified passcode entry shown after biometric auth. Accepts either
 * the app password or the duress PIN (4–64 chars, any character set);
 * core.authenticate() decides Normal vs Duress mode based on which
 * secret matched.
 *
 * Visually identical regardless of which secret is entered — the
 * observer cannot distinguish normal from duress authentication.
 *
 * Note on zeroization: JVM String is immutable — we can't guarantee
 * heap scrubbing. We clear the variable immediately after use and on
 * lifecycle pause. Rust core zeroizes the password after hashing
 * (ZeroizeOnDrop).
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
            contentDescription = localizationManager.t("a11y.app_locked"),
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = localizationManager.t("auth.unlock.title"),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = localizationManager.t("auth.unlock.body"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        val a11yInputLabel = localizationManager.t("auth.unlock.a11y_input")
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= MAX_PASSCODE_LENGTH) {
                    pin = value
                }
            },
            label = {
                Text(
                    localizationManager.t("auth.unlock.field_label"),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (pin.length >= MIN_PASSCODE_LENGTH) {
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
                        contentDescription = a11yInputLabel
                    },
        )

        Spacer(modifier = Modifier.height(24.dp))

        val a11yButtonLabel = localizationManager.t("auth.unlock.a11y_button")
        Button(
            onClick = {
                val entered = pin
                pin = ""
                onAuthenticate(entered)
            },
            enabled = pin.length >= MIN_PASSCODE_LENGTH,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = a11yButtonLabel
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
