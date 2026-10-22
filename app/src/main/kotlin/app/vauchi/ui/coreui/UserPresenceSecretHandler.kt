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
import kotlinx.coroutines.flow.StateFlow

/**
 * Fulfils core's `CreateUserPresenceSecret` / `RevealUserPresenceSecret`
 * (vauchi/private#712) with the Keystore and `BiometricPrompt`, answering
 * through [CoreAppViewModel.handleUserPresenceAnswer]. A host that cannot
 * show the check answers `HardwareUnavailable`.
 */
@Composable
fun UserPresenceSecretHandler(viewModel: CoreAppViewModel) {
    val context = LocalContext.current
    UserPresenceRequests(
        requests = viewModel.userPresenceRequest,
        consume = viewModel::consumeUserPresenceRequest,
        reply = viewModel::handleUserPresenceAnswer,
    ) { command ->
        val activity = context as? FragmentActivity
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canSealUserPresence(context)) {
            return@UserPresenceRequests UserPresenceSecretVault.UNAVAILABLE_EVENT_JSON
        }
        val keystore =
            runCatching { AndroidUserPresenceKeystore() }.getOrElse {
                return@UserPresenceRequests UserPresenceSecretVault.UNAVAILABLE_EVENT_JSON
            }
        UserPresenceSecretVault(
            keystore = keystore,
            prompt = BiometricUserPresencePrompt(activity),
            store = SharedPreferencesWrappedSecretStore(context),
        ).answer(command)
    }
}

/**
 * Runs [answer] for each request and replies with its event. A check torn
 * down with the screen answers cancelled, so core never waits on a prompt
 * that will not report back.
 */
@Composable
internal fun UserPresenceRequests(
    requests: StateFlow<UserPresenceCommand?>,
    consume: () -> Unit,
    reply: (String) -> Unit,
    answer: suspend (UserPresenceCommand) -> String,
) {
    val request by requests.collectAsState()

    LaunchedEffect(request) {
        val command = request ?: return@LaunchedEffect
        consume()
        var answered = false
        try {
            reply(answer(command))
            answered = true
        } finally {
            if (!answered) reply(UserPresenceSecretVault.CANCELLED_EVENT_JSON)
        }
    }
}
