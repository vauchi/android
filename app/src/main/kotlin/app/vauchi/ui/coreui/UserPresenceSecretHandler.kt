// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import app.vauchi.util.AndroidUserPresenceKeystore
import app.vauchi.util.BiometricUserPresencePrompt
import app.vauchi.util.SharedPreferencesWrappedSecretStore
import app.vauchi.util.canSealUserPresence

/**
 * Fulfils core's `CreateUserPresenceSecret` / `RevealUserPresenceSecret`
 * (vauchi/private#712) with the Keystore and `BiometricPrompt`, answering
 * through [CoreAppViewModel.handleUserPresenceAnswer]. A host that cannot
 * show the check answers `HardwareUnavailable`, and a check torn down with
 * the screen answers cancelled, so core never waits on a prompt that will
 * not report back.
 */
@Composable
fun UserPresenceSecretHandler(viewModel: CoreAppViewModel) {
    val context = LocalContext.current
    val request by viewModel.userPresenceRequest.collectAsState()

    LaunchedEffect(request) {
        val command = request ?: return@LaunchedEffect
        viewModel.consumeUserPresenceRequest()
        val activity = context as? FragmentActivity
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canSealUserPresence(context)) {
            viewModel.handleUserPresenceAnswer(UserPresenceSecretVault.UNAVAILABLE_EVENT_JSON)
            return@LaunchedEffect
        }
        val keystore =
            runCatching { AndroidUserPresenceKeystore() }.getOrElse {
                viewModel.handleUserPresenceAnswer(UserPresenceSecretVault.UNAVAILABLE_EVENT_JSON)
                return@LaunchedEffect
            }
        val vault =
            UserPresenceSecretVault(
                keystore = keystore,
                prompt = BiometricUserPresencePrompt(activity),
                store = SharedPreferencesWrappedSecretStore(context),
            )
        var answered = false
        try {
            viewModel.handleUserPresenceAnswer(vault.answer(command))
            answered = true
        } finally {
            if (!answered) viewModel.handleUserPresenceAnswer(UserPresenceSecretVault.CANCELLED_EVENT_JSON)
        }
    }
}
