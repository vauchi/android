// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instrumented tests for VauchiRepository FFI integration.
 * These tests run on an actual Android device/emulator where native libraries are loaded.
 *
 * Based on: features/identity_management.feature, features/contact_card_management.feature
 */
@RunWith(AndroidJUnit4::class)
class VauchiRepositoryFfiTest {
    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var repository: VauchiRepository
    private lateinit var storageKeyProvider: TestStorageKeyProvider

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create a unique temp directory for each test
        tempDir = File(context.cacheDir, "test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Shared provider so repos using the same data dir can decrypt each other's data
        storageKeyProvider = TestStorageKeyProvider()

        // Create a test context that uses our temp directory
        repository = createTestRepository(tempDir)
    }

    @After
    fun tearDown() {
        // Clean up temp directory
        tempDir.deleteRecursively()
    }

    /**
     * Creates a VauchiRepository for testing with a custom data directory.
     * Uses a wrapper context that redirects filesDir to our temp directory.
     * Accepts an optional StorageKeyProvider; defaults to the shared instance.
     */
    private fun createTestRepository(
        dataDir: File,
        provider: StorageKeyProvider = storageKeyProvider,
    ): VauchiRepository {
        val testContext = TestContextWrapper(context, dataDir)
        return VauchiRepository(testContext, provider)
    }

    // Based on: features/identity_management.feature

    /**
     * Scenario: First launch - no identity exists
     */
    @Test
    fun testNoIdentityOnFirstLaunch() {
        assertFalse(repository.hasIdentity(), "Should have no identity on first launch")
    }

    /**
     * Scenario: Create new identity with display name
     */
    @Test
    fun testCreateIdentity() {
        assertFalse(repository.hasIdentity())

        repository.createIdentity("Alice")

        assertTrue(repository.hasIdentity(), "Should have identity after creation")
        assertEquals("Alice", repository.getDisplayName())
    }

    /**
     * Scenario: Identity generates Ed25519 keypair
     */
    @Test
    fun testIdentityHasPublicId() {
        repository.createIdentity("Alice")

        val publicId = repository.getPublicId()

        assertFalse(publicId.isEmpty(), "Public ID should not be empty")
        // Ed25519 public key is 32 bytes = 64 hex chars
        assertEquals(64, publicId.length, "Public ID should be 64 hex characters")
    }

    /**
     * Scenario: Identity persists across sessions
     */
    @Test
    fun testIdentityPersistsAcrossSessions() {
        // First session - create identity
        repository.createIdentity("Alice")

        // Second session - create new repository with same data dir
        val repo2 = createTestRepository(tempDir)
        assertTrue(repo2.hasIdentity(), "Identity should persist across sessions")
        assertEquals("Alice", repo2.getDisplayName())
    }

    // Based on: features/contact_card_management.feature

    /**
     * Scenario: Initial card has display name only
     */
    @Test
    fun testInitialCardHasDisplayName() {
        repository.createIdentity("Alice")

        val card = repository.getOwnCard()

        assertEquals("Alice", card.displayName)
        assertTrue(card.fields.isEmpty(), "Initial card should have no fields")
    }
}
