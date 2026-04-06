// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import uniffi.vauchi_platform.KeychainException
import uniffi.vauchi_platform.MobilePlatformKeychain
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Adapts Android KeyStore to the [MobilePlatformKeychain] callback interface
 * expected by core's crypto-shredding operations (SMK management).
 *
 * Keys are encrypted with a dedicated AES-256-GCM KeyStore key and stored as
 * files in the app's private `keychain/` directory. Each file contains
 * `IV (12 bytes) || ciphertext || GCM tag (16 bytes)`.
 */
class PlatformKeychainBridge(
    private val context: Context,
) : MobilePlatformKeychain {
    companion object {
        private const val KEYSTORE_ALIAS = "vauchi_keychain_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val KEYCHAIN_DIR = "keychain"
    }

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val keychainDir: File
        get() = File(context.filesDir, KEYCHAIN_DIR).also { it.mkdirs() }

    override fun saveKey(
        name: String,
        key: ByteArray,
    ) {
        try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(key)
            File(keychainDir, name).writeBytes(iv + encrypted)
        } catch (e: Exception) {
            throw KeychainException.OperationFailed("saveKey($name): ${e.message}")
        }
    }

    override fun loadKey(name: String): ByteArray? {
        val file = File(keychainDir, name)
        if (!file.exists()) return null
        try {
            val data = file.readBytes()
            if (data.size < GCM_IV_LENGTH + 1) {
                throw IllegalArgumentException("Encrypted data too short")
            }
            val iv = data.sliceArray(0 until GCM_IV_LENGTH)
            val encrypted = data.sliceArray(GCM_IV_LENGTH until data.size)
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            return cipher.doFinal(encrypted)
        } catch (e: Exception) {
            throw KeychainException.OperationFailed("loadKey($name): ${e.message}")
        }
    }

    override fun deleteKey(name: String) {
        try {
            val file = File(keychainDir, name)
            if (file.exists()) {
                // Overwrite before delete to reduce data remanence
                file.writeBytes(ByteArray(file.length().toInt()))
                file.delete()
            }
        } catch (e: Exception) {
            throw KeychainException.OperationFailed("deleteKey($name): ${e.message}")
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) return existingEntry.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec
                .Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
