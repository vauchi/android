// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
class KeyStoreHelper {
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

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Get or generate the master key from Android KeyStore.
     * This key is used to encrypt/decrypt the storage key.
     */
    private fun getOrCreateMasterKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        // Generate new key in KeyStore
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * Generate a new random storage key and encrypt it with the master key.
     * Returns the encrypted storage key bytes (IV + ciphertext + tag).
     */
    fun generateEncryptedStorageKey(): ByteArray {
        val storageKey = uniffi.vauchi_mobile.generateStorageKey()
        return encryptStorageKey(storageKey)
    }

    /**
     * Encrypt a storage key using the master key.
     *
     * @throws AuthenticationRequiredException if the device has not been unlocked recently.
     */
    fun encryptStorageKey(storageKey: ByteArray): ByteArray {
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
                "Device must be unlocked to access encryption keys", e
            )
        }
    }

    /**
     * Decrypt a storage key using the master key.
     *
     * @throws AuthenticationRequiredException if the device has not been unlocked recently.
     */
    fun decryptStorageKey(encryptedData: ByteArray): ByteArray {
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
                "Device must be unlocked to access encryption keys", e
            )
        }
    }

    /**
     * Check if a master key exists in the KeyStore.
     */
    fun hasMasterKey(): Boolean {
        return keyStore.containsAlias(KEYSTORE_ALIAS)
    }

    /**
     * Delete the master key from KeyStore (for testing/reset).
     */
    fun deleteMasterKey() {
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
    cause: Throwable? = null
) : Exception(message, cause)
