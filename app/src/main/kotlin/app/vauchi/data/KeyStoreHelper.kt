// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction for storage key encryption operations.
 * Production uses Android KeyStore; tests can use a simpler implementation.
 */
interface StorageKeyProvider {
    fun generateEncryptedStorageKey(): ByteArray

    fun encryptStorageKey(storageKey: ByteArray): ByteArray

    fun decryptStorageKey(encryptedData: ByteArray): ByteArray

    fun hasMasterKey(): Boolean

    fun deleteMasterKey()
}

/**
 * Helper class for secure key management using Android KeyStore.
 *
 * The storage encryption key is generated and stored in the Android KeyStore,
 * which provides hardware-backed security on supported devices.
 *
 * Keys require user authentication (device unlock via PIN/pattern/biometric)
 * within the last [AUTH_VALIDITY_SECONDS] seconds. This satisfies
 * OWASP MASVS-STORAGE-2.
 */
class KeyStoreHelper : StorageKeyProvider {
    companion object {
        private const val KEYSTORE_ALIAS = "vauchi_storage_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH = 128
        private const val STORAGE_KEY_LENGTH = 32 // 256-bit key for AES

        // Prefix for encrypted data: 12-byte IV + ciphertext + 16-byte tag
        private const val GCM_IV_LENGTH = 12

        // Key is usable for 5 minutes after device unlock (PIN/pattern/biometric).
        // This avoids excessive authentication prompts while still requiring
        // recent user presence verification.
        private const val AUTH_VALIDITY_SECONDS = 300
    }

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

    /**
     * Get or generate the master key from Android KeyStore.
     * This key is used to encrypt/decrypt the storage key.
     */
    private fun getOrCreateMasterKey(): SecretKey {
        try {
            val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingKey != null) {
                return existingKey.secretKey
            }
        } catch (e: UnrecoverableKeyException) {
            throw DeviceNotSecureException(
                "A secure lock screen (PIN, pattern, or biometric) is required to protect your data. " +
                    "Please set one up in your device Settings.",
                e,
            )
        } catch (e: KeyStoreException) {
            throw DeviceNotSecureException(
                "A secure lock screen (PIN, pattern, or biometric) is required to protect your data. " +
                    "Please set one up in your device Settings.",
                e,
            )
        }

        // Generate new key in KeyStore
        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )

        val keySpec =
            KeyGenParameterSpec
                .Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(!app.vauchi.BuildConfig.DEBUG) // Disabled for device testing
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            AUTH_VALIDITY_SECONDS,
                            KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                    }
                }.build()

        try {
            keyGenerator.init(keySpec)
            return keyGenerator.generateKey()
        } catch (e: InvalidAlgorithmParameterException) {
            throw DeviceNotSecureException(
                "A secure lock screen (PIN, pattern, or biometric) is required to protect your data. " +
                    "Please set one up in your device Settings.",
                e,
            )
        }
    }

    /**
     * Generate a new random storage key and encrypt it with the master key.
     * Returns the encrypted storage key bytes (IV + ciphertext + tag).
     */
    override fun generateEncryptedStorageKey(): ByteArray {
        val storageKey = uniffi.vauchi_platform.generateStorageKey()
        return encryptStorageKey(storageKey)
    }

    /**
     * Encrypt a storage key using the master key.
     *
     * @throws AuthenticationRequiredException if the device has not been unlocked recently.
     */
    override fun encryptStorageKey(storageKey: ByteArray): ByteArray {
        try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(storageKey)

            // Return IV + encrypted data
            return iv + encrypted
        } catch (e: UserNotAuthenticatedException) {
            throw AuthenticationRequiredException(
                "Device must be unlocked to access encryption keys",
                e,
            )
        }
    }

    /**
     * Decrypt a storage key using the master key.
     *
     * @throws AuthenticationRequiredException if the device has not been unlocked recently.
     */
    override fun decryptStorageKey(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH + STORAGE_KEY_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted data length")
        }

        try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            val iv = encryptedData.sliceArray(0 until GCM_IV_LENGTH)
            val encrypted = encryptedData.sliceArray(GCM_IV_LENGTH until encryptedData.size)

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

            return cipher.doFinal(encrypted)
        } catch (e: UserNotAuthenticatedException) {
            throw AuthenticationRequiredException(
                "Device must be unlocked to access encryption keys",
                e,
            )
        }
    }

    /**
     * Check if a master key exists in the KeyStore.
     */
    override fun hasMasterKey(): Boolean = keyStore.containsAlias(KEYSTORE_ALIAS)

    /**
     * Delete the master key from KeyStore (for testing/reset).
     */
    override fun deleteMasterKey() {
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }
}

/**
 * Thrown when KeyStore operations require user authentication (device unlock)
 * but the device has not been unlocked within the required time window.
 *
 * The caller should prompt the user to unlock their device (e.g., via
 * [android.app.KeyguardManager.createConfirmDeviceCredentialIntent]) and retry.
 */
class AuthenticationRequiredException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when the device does not have a secure lock screen (PIN, pattern, or biometric)
 * configured, which is required for Android KeyStore user-authentication-bound keys.
 *
 * The caller should display an error message instructing the user to set up a
 * secure lock screen in device Settings, then retry.
 */
class DeviceNotSecureException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
