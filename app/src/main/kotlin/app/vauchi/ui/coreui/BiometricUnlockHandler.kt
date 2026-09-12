// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import app.vauchi.util.BiometricHelper
import app.vauchi.util.LocalizationManager

/**
 * Fulfils core's `Command::RequestBiometricUnlock` with the OS biometric
 * prompt (device-credential fallback included). Hosted by
 * [app.vauchi.ui.presentation.PresentationHost], the tree that draws the
 * lock screen whose biometric action emits the command.
 *
 * Success → `BiometricUnlockSucceeded`, which core answers with its
 * duress-aware unlock outcome (ADR-031). A failed prompt →
 * `HardwareError`; a device without enrolled biometrics or a host that
 * cannot show a prompt → `HardwareUnavailable`, so core never waits on a
 * prompt that will not report back. A prompt the user dismissed reports
 * nothing: core would surface `HardwareError` as an alert, and the lock
 * screen the user is already looking at is the whole answer.
 */
@Composable
fun BiometricUnlockHandler(viewModel: CoreAppViewModel) {
    val context = LocalContext.current
    val request by viewModel.biometricUnlockRequest.collectAsState()

    LaunchedEffect(request) {
        if (request != true) return@LaunchedEffect
        viewModel.consumeBiometricUnlockRequest()
        val activity = context as? FragmentActivity
        if (activity == null || !BiometricHelper.canAuthenticate(context)) {
            viewModel.handleBiometricUnlockUnavailable()
            return@LaunchedEffect
        }
        val localization = LocalizationManager.getInstance(context)
        BiometricHelper.authenticate(
            activity = activity,
            title = localization.t("auth.unlock.title"),
            subtitle = localization.t("auth.biometric_prompt_subtitle"),
            onSuccess = viewModel::handleBiometricUnlockSucceeded,
            onError = { message -> message?.let(viewModel::handleBiometricUnlockFailed) },
        )
    }
}
