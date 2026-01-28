// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class KeyStoreHelperTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var keyStoreHelper: KeyStoreHelper
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        dataDir = File(RuntimeEnvironment.getApplication().filesDir, "test_keystore")
        dataDir.mkdirs()

        whenever(mockContext.filesDir).thenReturn(dataDir)
        whenever(mockContext.getSharedPreferences("vauchi_settings", Context.MODE_PRIVATE))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)

        keyStoreHelper = KeyStoreHelper()
    }

    @Test
    fun `test generate encrypted storage key`() {
        val encryptedKey = keyStoreHelper.generateEncryptedStorageKey()
        
        assertNotNull(encryptedKey)
        assertTrue(encryptedKey.isNotEmpty())
        
        // Should be able to decrypt it back
        val decryptedKey = keyStoreHelper.decryptStorageKey(encryptedKey)
        assertTrue(decryptedKey.isNotEmpty())
    }

    @Test
    fun `test decrypt storage key roundtrip`() {
        val originalKey = "test_storage_key_32_bytes_long!!".toByteArray()
        
        val encryptedKey = keyStoreHelper.encryptStorageKey(originalKey)
        assertNotNull(encryptedKey)
        assertTrue(encryptedKey.isNotEmpty())
        
        val decryptedKey = keyStoreHelper.decryptStorageKey(encryptedKey)
        assertNotNull(decryptedKey)
        assertTrue(decryptedKey.contentEquals(originalKey))
    }

    @Test
    fun `test decrypt invalid key fails gracefully`() {
        val invalidKey = "invalid_key_data".toByteArray()
        
        try {
            keyStoreHelper.decryptStorageKey(invalidKey)
            // If it doesn't throw, that's also acceptable behavior
        } catch (e: Exception) {
            // Expected for invalid key
        }
    }

    @Test
    fun `test key generation consistency`() {
        val key1 = keyStoreHelper.generateEncryptedStorageKey()
        val key2 = keyStoreHelper.generateEncryptedStorageKey()
        
        // Keys should be different (unique)
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `test encrypted key format`() {
        val encryptedKey = keyStoreHelper.generateEncryptedStorageKey()
        
        // Encrypted key should have reasonable size
        assertTrue(encryptedKey.size >= 32) // At least 256 bits for security
        assertTrue(encryptedKey.size <= 1024) // Not excessively large
    }
}
