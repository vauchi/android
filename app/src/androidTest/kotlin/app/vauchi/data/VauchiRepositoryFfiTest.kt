// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.vauchi_platform.MobileFieldType
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumented tests for VauchiRepository FFI integration.
 * These tests run on an actual Android device/emulator where native libraries are loaded.
 *
 * Based on: features/identity_management.feature, features/contact_card_management.feature,
 *           features/contact_exchange.feature, features/account_recovery.feature
 */
@RunWith(AndroidJUnit4::class)
class VauchiRepositoryFfiTest {
    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var repository: VauchiRepository
    private lateinit var storageKeyProvider: TestStorageKeyProvider

    companion object {
        /** Local dev relay URL (started via `just dev-relay`) */
        private const val LOCAL_RELAY_URL = "ws://127.0.0.1:8080"
        private const val LOCAL_RELAY_PORT = 8080
    }

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

    /**
     * Skip test if local dev relay is not running at 127.0.0.1:8080.
     * Start with: just dev-relay
     */
    private fun assumeLocalRelay() {
        val reachable =
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", LOCAL_RELAY_PORT), 500)
                    true
                }
            } catch (_: Exception) {
                false
            }
        Assume.assumeTrue(
            "Local relay not running at $LOCAL_RELAY_URL — start with: just dev-relay",
            reachable,
        )
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

    /**
     * Scenario: Add email field to card
     */
    @Test
    fun testAddEmailField() {
        repository.createIdentity("Alice")

        repository.addField(MobileFieldType.EMAIL, "Work", "alice@company.com")

        val card = repository.getOwnCard()
        assertEquals(1, card.fields.size)
        assertEquals(MobileFieldType.EMAIL, card.fields[0].fieldType)
        assertEquals("Work", card.fields[0].label)
        assertEquals("alice@company.com", card.fields[0].value)
    }

    /**
     * Scenario: Add phone field to card
     */
    @Test
    fun testAddPhoneField() {
        repository.createIdentity("Alice")

        repository.addField(MobileFieldType.PHONE, "Mobile", "+1234567890")

        val card = repository.getOwnCard()
        assertEquals(1, card.fields.size)
        assertEquals(MobileFieldType.PHONE, card.fields[0].fieldType)
        assertEquals("Mobile", card.fields[0].label)
        assertEquals("+1234567890", card.fields[0].value)
    }

    /**
     * Scenario: Update field value
     */
    @Test
    fun testUpdateFieldValue() {
        repository.createIdentity("Alice")
        repository.addField(MobileFieldType.PHONE, "Mobile", "+1234567890")

        repository.updateField("Mobile", "+0987654321")

        val card = repository.getOwnCard()
        assertEquals("+0987654321", card.fields[0].value)
    }

    /**
     * Scenario: Remove field from card
     */
    @Test
    fun testRemoveField() {
        repository.createIdentity("Alice")
        repository.addField(MobileFieldType.EMAIL, "Work", "alice@company.com")

        val removed = repository.removeField("Work")

        assertTrue(removed, "removeField should return true")
        val card = repository.getOwnCard()
        assertTrue(card.fields.isEmpty(), "Field should be removed")
    }

    // Based on: features/contacts_management.feature

    /**
     * Scenario: Empty contacts list on first launch
     */
    @Test
    fun testEmptyContactsList() {
        repository.createIdentity("Alice")

        val contacts = repository.listContactsPaginated(0u, 10u)

        assertTrue(contacts.isEmpty(), "Contact list should be empty initially")
        assertEquals(0u, repository.contactCount())
    }

    // Based on: features/identity_management.feature

    /**
     * Scenario: Export encrypted backup
     */
    @Test
    fun testExportBackup() {
        repository.createIdentity("Alice")
        repository.addField(MobileFieldType.EMAIL, "Work", "alice@company.com")

        val backup = repository.exportBackup("correct-horse-battery-staple")

        assertFalse(backup.isEmpty(), "Backup should not be empty")
    }

    /**
     * Scenario: Import backup restores identity
     */
    @Test
    fun testImportBackup() {
        // Create identity and export backup
        repository.createIdentity("Alice")
        repository.addField(MobileFieldType.EMAIL, "Work", "alice@company.com")
        val backupPassword = "correct-horse-battery-staple"
        val backupData = repository.exportBackup(backupPassword)

        // Create new repository and import backup
        val newDir = File(context.cacheDir, "new_${System.currentTimeMillis()}")
        newDir.mkdirs()
        try {
            val repo2 = createTestRepository(newDir, TestStorageKeyProvider())
            repo2.importBackup(backupData, backupPassword)

            assertTrue(repo2.hasIdentity())
            assertEquals("Alice", repo2.getDisplayName())
        } finally {
            newDir.deleteRecursively()
        }
    }

    /**
     * Scenario: List available social networks
     */
    @Test
    fun testListSocialNetworks() {
        val networks = repository.listSocialNetworks()

        assertFalse(networks.isEmpty(), "Should have default social networks")
    }

    /**
     * Scenario: Get profile URL for social network
     */
    @Test
    fun testGetProfileUrl() {
        val url = repository.getProfileUrl("github", "octocat")

        assertEquals("https://github.com/octocat", url)
    }
}
