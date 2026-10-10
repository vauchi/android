// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import uniffi.vauchi_platform.KeychainException
import uniffi.vauchi_platform.MobilePlatformKeychain
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The storage key this app kept itself before Core held its keys in the
 * keychain (vauchi/private#580).
 */
interface LegacyStorageKey {
    /** The key, or `null` when there is none; throws as [KeyStoreHelper] does. */
    fun load(): ByteArray?

    fun delete()
}

/** The old key: a SharedPreferences blob encrypted under `vauchi_storage_key`. */
class PreferencesLegacyStorageKey(
    context: Context,
    private val provider: StorageKeyProvider = KeyStoreHelper(),
) : LegacyStorageKey {
    private val prefs = context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ByteArray? {
        val blob = prefs.getString(PREF_ENCRYPTED_STORAGE_KEY, null) ?: return null
        return provider.decryptStorageKey(Base64.decode(blob, Base64.DEFAULT))
    }

    override fun delete() {
        prefs.edit().remove(PREF_ENCRYPTED_STORAGE_KEY).apply()
        provider.deleteMasterKey()
    }

    companion object {
        const val PREF_ENCRYPTED_STORAGE_KEY = "encrypted_storage_key"
    }
}

/**
 * Adapts Android KeyStore to Core's [MobilePlatformKeychain]: Core keeps every
 * key that opens the database here, so a shred that deletes them destroys
 * access (ADR-033).
 *
 * Keys are encrypted with a dedicated AES-256-GCM KeyStore key and stored as
 * files in the app's private `keychain/` directory. Each file contains
 * `IV (12 bytes) || ciphertext || GCM tag (16 bytes)`.
 *
 * Until Core stores its own bootstrap key, the bootstrap name is served from
 * the [LegacyStorageKey], and deleting it deletes the old key too.
 */
class PlatformKeychainBridge(
    private val context: Context,
    private val legacy: LegacyStorageKey = PreferencesLegacyStorageKey(context),
    requireUserAuth: Boolean = UserAuthPolicy.required,
    private val masterKey: () -> SecretKey = { getOrCreateMasterKey(requireUserAuth) },
) : MobilePlatformKeychain {
    companion object {
        private const val KEYSTORE_ALIAS = "vauchi_keychain_key_v2"

        /** Unbound key from before #287 A1; it never wrapped a file in use. */
        private const val UNBOUND_KEYSTORE_ALIAS = "vauchi_keychain_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val KEYCHAIN_DIR = "keychain"
        private const val BOOTSTRAP_KEY_NAME = "storage_bootstrap"

        private fun getOrCreateMasterKey(requireUserAuth: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(UNBOUND_KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(UNBOUND_KEYSTORE_ALIAS)
            }
            val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingEntry != null) return existingEntry.secretKey

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec =
                KeyGenParameterSpec
                    .Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(requireUserAuth)
                    .apply {
                        if (requireUserAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(
                                UserAuthPolicy.VALIDITY_SECONDS,
                                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                            )
                        } else if (requireUserAuth) {
                            @Suppress("DEPRECATION")
                            setUserAuthenticationValidityDurationSeconds(UserAuthPolicy.VALIDITY_SECONDS)
                        }
                    }.build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }

    private val keychainDir: File
        get() = File(context.filesDir, KEYCHAIN_DIR).also { it.mkdirs() }

    override fun saveKey(
        name: String,
        key: ByteArray,
    ) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(key)
            File(keychainDir, name).writeBytes(iv + encrypted)
        } catch (e: Exception) {
            throw keychainFailure("saveKey", e)
        }
    }

    override fun loadKey(name: String): ByteArray? {
        val file = File(keychainDir, name)
        try {
            if (!file.exists()) {
                return if (name == BOOTSTRAP_KEY_NAME) legacy.load() else null
            }
            val data = file.readBytes()
            if (data.size < GCM_IV_LENGTH + 1) {
                throw IllegalArgumentException("Encrypted data too short")
            }
            val iv = data.sliceArray(0 until GCM_IV_LENGTH)
            val encrypted = data.sliceArray(GCM_IV_LENGTH until data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            return cipher.doFinal(encrypted)
        } catch (e: Exception) {
            throw keychainFailure("loadKey", e)
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
            if (name == BOOTSTRAP_KEY_NAME) {
                legacy.delete()
            }
            if (keychainDir.listFiles().isNullOrEmpty()) {
                deleteMasterKey()
            }
        } catch (e: Exception) {
            throw keychainFailure("deleteKey", e)
        }
    }

    /**
     * The Keystore key goes with the last key file (vauchi/private#599): a
     * copy of `files/` taken before a shred holds the key files, and a
     * surviving Keystore key would open them.
     */
    private fun deleteMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    /**
     * Names the failure for Core, which chooses the screen (ADR-045). Only the
     * exception class travels: platform messages can name paths.
     */
    private fun keychainFailure(
        operation: String,
        e: Exception,
    ): KeychainException =
        when (e) {
            is KeychainException -> {
                e
            }

            is UserNotAuthenticatedException, is AuthenticationRequiredException -> {
                KeychainException.AuthenticationRequired()
            }

            is KeyPermanentlyInvalidatedException, is KeyInvalidatedException, is AEADBadTagException -> {
                KeychainException.KeyInvalidated()
            }

            else -> {
                KeychainException.OperationFailed("$operation: ${e.javaClass.simpleName}")
            }
        }
}
