// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.vauchi.ui.coreui.UserPresenceBinding
import app.vauchi.ui.coreui.UserPresenceCheck
import app.vauchi.ui.coreui.UserPresenceKeystore
import app.vauchi.ui.coreui.UserPresencePrompt
import app.vauchi.ui.coreui.WrappedSecret
import app.vauchi.ui.coreui.WrappedSecretStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val USER_PRESENCE_KEY_ALIAS = "vauchi_user_presence_key"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val PER_USE = 0

private const val USER_PRESENCE_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Whether this device can seal a secret behind the OS user-presence check
 * (core's `can_seal_user_presence`, vauchi/private#712): a strong biometric
 * or a device credential is set up and the Android Keystore loads.
 *
 * API 30+ only. Before Android 11 a key that needs authentication on every
 * use can be unlocked by a biometric alone — a `BiometricPrompt` with a
 * `CryptoObject` may not offer the device credential there — so the
 * credential fallback core's D4 relies on would be missing. Those devices
 * report `false` and core verifies with the app password instead.
 */
internal fun canSealUserPresence(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val canAuthenticate =
        BiometricManager.from(context).canAuthenticate(USER_PRESENCE_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    return canAuthenticate && runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).load(null) }.isSuccess
}

/**
 * The key and prompt for each check Core names (vauchi/private#778). A
 * biometric key takes only a strong biometric and dies when the enrolment
 * changes: enrolling needs only the device credential, which must not open
 * it. A credential key takes only the credential.
 */
internal class UserPresenceSeal private constructor(
    val keyAuthenticators: Int,
    val invalidatedByBiometricEnrollment: Boolean,
) {
    companion object {
        fun of(binding: UserPresenceBinding): UserPresenceSeal =
            when (binding) {
                UserPresenceBinding.BIOMETRY -> UserPresenceSeal(KeyProperties.AUTH_BIOMETRIC_STRONG, true)
                UserPresenceBinding.DEVICE_CREDENTIAL -> UserPresenceSeal(KeyProperties.AUTH_DEVICE_CREDENTIAL, false)
            }

        /** A key sealed before 0.88.0 records no binding and takes either check. */
        fun promptAuthenticators(binding: UserPresenceBinding?): Int =
            when (binding) {
                UserPresenceBinding.BIOMETRY -> BiometricManager.Authenticators.BIOMETRIC_STRONG
                UserPresenceBinding.DEVICE_CREDENTIAL -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
                null -> USER_PRESENCE_AUTHENTICATORS
            }
    }
}

/**
 * The Keystore key a user-presence secret is wrapped with. Every use needs a
 * fresh check (timeout 0) of the kind [UserPresenceSeal] names.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class AndroidUserPresenceKeystore : UserPresenceKeystore {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun newEncryptCipher(binding: UserPresenceBinding): Cipher {
        val seal = UserPresenceSeal.of(binding)
        delete()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(USER_PRESENCE_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(PER_USE, seal.keyAuthenticators)
                .setInvalidatedByBiometricEnrollment(seal.invalidatedByBiometricEnrollment)
                .build(),
        )
        val key = generator.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** `null` once the OS invalidated the key (lock screen removed, or a biometric enrolment changed). */
    override fun decryptCipher(iv: ByteArray): Cipher? {
        val key = keyStore.getKey(USER_PRESENCE_KEY_ALIAS, null) as? SecretKey ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    override fun delete() {
        if (keyStore.containsAlias(USER_PRESENCE_KEY_ALIAS)) keyStore.deleteEntry(USER_PRESENCE_KEY_ALIAS)
    }
}

/** App-private storage for the wrapped secret; the key never leaves the Keystore. */
internal class SharedPreferencesWrappedSecretStore(
    context: Context,
) : WrappedSecretStore {
    private val prefs = context.getSharedPreferences("vauchi_user_presence", Context.MODE_PRIVATE)

    override fun save(sealed: WrappedSecret) {
        prefs
            .edit()
            .putString(IV, Base64.encodeToString(sealed.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
            .putString(BINDING, sealed.binding?.wire)
            .commit()
    }

    override fun load(): WrappedSecret? {
        val iv = prefs.getString(IV, null) ?: return null
        val ciphertext = prefs.getString(CIPHERTEXT, null) ?: return null
        val binding = UserPresenceBinding.fromWire(prefs.getString(BINDING, null))
        return runCatching {
            WrappedSecret(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(ciphertext, Base64.NO_WRAP), binding)
        }.getOrNull()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"
        const val BINDING = "binding"
    }
}

/**
 * The OS user-presence check as a `BiometricPrompt` bound to the cipher, so
 * only an authentication that unlocked the key returns it. Core's prompt is
 * the dialog's title. With the device credential allowed the system draws
 * its own cancel action; a biometric-only prompt needs a negative button, so
 * it takes the system's own localized "Cancel".
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class BiometricUserPresencePrompt(
    private val activity: FragmentActivity,
) : UserPresencePrompt {
    override suspend fun authenticate(
        reason: String,
        cipher: Cipher,
        binding: UserPresenceBinding?,
    ): UserPresenceCheck =
        suspendCancellableCoroutine { continuation ->
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!continuation.isActive) return
                        val unlocked = result.cryptoObject?.cipher
                        continuation.resume(
                            if (unlocked != null) UserPresenceCheck.Unlocked(unlocked) else UserPresenceCheck.Failed,
                        )
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED,
                                -> UserPresenceCheck.Cancelled

                                else -> UserPresenceCheck.Failed
                            },
                        )
                    }
                }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val authenticators = UserPresenceSeal.promptAuthenticators(binding)
            val info =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(reason)
                    .setAllowedAuthenticators(authenticators)
            if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                info.setNegativeButtonText(activity.getString(android.R.string.cancel))
            }
            prompt.authenticate(
                info.build(),
                BiometricPrompt.CryptoObject(cipher),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
