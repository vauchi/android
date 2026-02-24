// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Simple in-memory storage key provider for instrumented tests.
 * Uses a software AES key without Android KeyStore or user-authentication requirements.
 */
class TestStorageKeyProvider : StorageKeyProvider {
    private val masterKey: SecretKey =
        KeyGenerator
            .getInstance("AES")
            .apply {
                init(256)
            }.generateKey()

    private companion object {
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }

    override fun generateEncryptedStorageKey(): ByteArray {
        val storageKey = uniffi.vauchi_mobile.generateStorageKey()
        return encryptStorageKey(storageKey)
    }

    override fun encryptStorageKey(storageKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(storageKey)
        return iv + encrypted
    }

    override fun decryptStorageKey(encryptedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = encryptedData.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = encryptedData.sliceArray(GCM_IV_LENGTH until encryptedData.size)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        return cipher.doFinal(encrypted)
    }

    override fun hasMasterKey(): Boolean = true

    override fun deleteMasterKey() {
        // No-op for tests
    }
}
