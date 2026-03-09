// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Helper for gating sensitive operations behind biometric / device credential authentication.
 * Matches the iOS pattern using LAContext.evaluatePolicy with fallback to device passcode.
 */
object BiometricHelper {
    /**
     * Returns true if biometric or device credential authentication is available.
     */
    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val result =
            biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows biometric prompt (with device credential fallback) and invokes [onSuccess]
     * if authentication succeeds, or [onError] on failure/cancellation.
     *
     * @param activity The FragmentActivity hosting the prompt.
     * @param title Title shown in the biometric dialog.
     * @param subtitle Optional subtitle for context.
     * @param onSuccess Called on successful authentication.
     * @param onError Called with error message on failure. Null message means user cancelled.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onError: (String?) -> Unit = {},
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(null)
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // Individual attempt failed — prompt stays open for retry
                }
            }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ).build()

        prompt.authenticate(promptInfo)
    }
}
