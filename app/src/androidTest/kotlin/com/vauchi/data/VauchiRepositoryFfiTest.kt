// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.vauchi_mobile.MobileFieldType
import java.io.File
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

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create a unique temp directory for each test
        tempDir = File(context.cacheDir, "test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

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
     */
    private fun createTestRepository(dataDir: File): VauchiRepository {
        val testContext = TestContextWrapper(context, dataDir)
        return VauchiRepository(testContext)
    }

    // MARK: - Identity Management Tests
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

    // MARK: - Contact Card Tests
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

    // MARK: - Contact Exchange Tests
    // Based on: features/contact_exchange.feature

    /**
     * Scenario: Generate exchange QR code
     */
    @Test
    fun testGenerateExchangeQr() {
        repository.createIdentity("Alice")

        val exchangeData = repository.generateExchangeQr()

        assertTrue(exchangeData.qrData.startsWith("wb://"), "QR data should start with wb://")
        assertFalse(exchangeData.publicId.isEmpty())
        assertTrue(exchangeData.expiresAt > System.currentTimeMillis() / 1000)
    }

    /**
     * Scenario: Complete contact exchange between two users
     */
    @Test
    fun testCompleteContactExchange() {
        // Create Alice's temp dir and repository
        val aliceDir = File(context.cacheDir, "alice_${System.currentTimeMillis()}")
        aliceDir.mkdirs()
        val aliceRepo = createTestRepository(aliceDir)
        aliceRepo.createIdentity("Alice")
        aliceRepo.addField(MobileFieldType.EMAIL, "Work", "alice@company.com")

        // Create Bob's temp dir and repository
        val bobDir = File(context.cacheDir, "bob_${System.currentTimeMillis()}")
        bobDir.mkdirs()
        val bobRepo = createTestRepository(bobDir)
        bobRepo.createIdentity("Bob")
        bobRepo.addField(MobileFieldType.PHONE, "Mobile", "+1234567890")

        try {
            // Alice generates QR code
            val aliceExchange = aliceRepo.generateExchangeQr()
            assertFalse(aliceExchange.qrData.isEmpty())

            // Bob scans Alice's QR and completes exchange
            val bobResult = bobRepo.completeExchange(aliceExchange.qrData)
            assertTrue(bobResult.success, "Bob's exchange should succeed: ${bobResult.errorMessage}")
            assertEquals("Alice", bobResult.contactName)

            // Bob now has Alice as a contact
            val bobContacts = bobRepo.listContacts()
            assertEquals(1, bobContacts.size)
            assertEquals("Alice", bobContacts[0].displayName)

            // Bob generates QR for Alice
            val bobExchange = bobRepo.generateExchangeQr()

            // Alice scans Bob's QR and completes exchange
            val aliceResult = aliceRepo.completeExchange(bobExchange.qrData)
            assertTrue(aliceResult.success, "Alice's exchange should succeed: ${aliceResult.errorMessage}")
            assertEquals("Bob", aliceResult.contactName)

            // Alice now has Bob as a contact
            val aliceContacts = aliceRepo.listContacts()
            assertEquals(1, aliceContacts.size)
            assertEquals("Bob", aliceContacts[0].displayName)
        } finally {
            aliceDir.deleteRecursively()
            bobDir.deleteRecursively()
        }
    }

    // MARK: - Contact Management Tests
    // Based on: features/contacts_management.feature

    /**
     * Scenario: Empty contacts list on first launch
     */
    @Test
    fun testEmptyContactsList() {
        repository.createIdentity("Alice")

        val contacts = repository.listContacts()

        assertTrue(contacts.isEmpty(), "Contact list should be empty initially")
        assertEquals(0u, repository.contactCount())
    }

    // MARK: - Backup Tests
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
        val backupData = repository.exportBackup("password123")

        // Create new repository and import backup
        val newDir = File(context.cacheDir, "new_${System.currentTimeMillis()}")
        newDir.mkdirs()
        try {
            val repo2 = createTestRepository(newDir)
            repo2.importBackup(backupData, "password123")

            assertTrue(repo2.hasIdentity())
            assertEquals("Alice", repo2.getDisplayName())
        } finally {
            newDir.deleteRecursively()
        }
    }

    // MARK: - Recovery Tests
    // Based on: features/account_recovery.feature

    /**
     * Scenario: Create recovery claim
     */
    @Test
    fun testCreateRecoveryClaim() {
        repository.createIdentity("Alice")

        // Simulate old public key (64 hex chars = 32 bytes Ed25519 key)
        val oldPkHex = "a".repeat(64)

        val claim = repository.createRecoveryClaim(oldPkHex)

        assertEquals(oldPkHex, claim.oldPublicKey)
        assertEquals(repository.getPublicId(), claim.newPublicKey)
        assertFalse(claim.claimData.isEmpty(), "Claim data should not be empty")
        assertFalse(claim.isExpired, "Fresh claim should not be expired")
    }

    /**
     * Scenario: Get recovery status after creating claim
     */
    @Test
    fun testRecoveryStatusAfterClaimCreation() {
        repository.createIdentity("Alice")

        val oldPkHex = "d".repeat(64)
        repository.createRecoveryClaim(oldPkHex)

        val status = repository.getRecoveryStatus()

        assertNotNull(status, "Should have active recovery after claim creation")
        assertEquals(oldPkHex, status!!.oldPublicKey)
        assertEquals(0u, status.vouchersCollected)
        assertTrue(status.vouchersNeeded > 0u, "Should need at least 1 voucher")
        assertFalse(status.isComplete)
    }

    /**
     * Scenario: Add voucher to recovery claim and check progress
     * Tests: features/account_recovery.feature - "collect vouchers"
     */
    @Test
    fun testAddRecoveryVoucher() {
        // Alice creates a claim
        val aliceDir = File(context.cacheDir, "alice_recovery_${System.currentTimeMillis()}")
        aliceDir.mkdirs()
        val aliceRepo = createTestRepository(aliceDir)
        aliceRepo.createIdentity("Alice")

        val oldPkHex = "3".repeat(64)
        val claim = aliceRepo.createRecoveryClaim(oldPkHex)

        // Initial status: 0 vouchers collected
        val initialStatus = aliceRepo.getRecoveryStatus()
        assertNotNull(initialStatus)
        assertEquals(0u, initialStatus!!.vouchersCollected)

        // Bob vouches for Alice
        val bobDir = File(context.cacheDir, "bob_recovery_${System.currentTimeMillis()}")
        bobDir.mkdirs()
        val bobRepo = createTestRepository(bobDir)
        bobRepo.createIdentity("Bob")

        try {
            val voucher = bobRepo.createRecoveryVoucher(claim.claimData)

            // Alice adds Bob's voucher
            val progress = aliceRepo.addRecoveryVoucher(voucher.voucherData)

            assertEquals(1u, progress.vouchersCollected, "Should have 1 voucher after Bob vouches")
            assertEquals(oldPkHex, progress.oldPublicKey)
            assertFalse(progress.isComplete, "Should not be complete with only 1 voucher")

            // Verify status reflects the voucher
            val updatedStatus = aliceRepo.getRecoveryStatus()
            assertEquals(1u, updatedStatus!!.vouchersCollected)
        } finally {
            aliceDir.deleteRecursively()
            bobDir.deleteRecursively()
        }
    }

    /**
     * Scenario: Create voucher for someone's recovery claim
     */
    @Test
    fun testCreateVoucherForClaim() {
        // Alice creates a claim
        val aliceDir = File(context.cacheDir, "alice_voucher_${System.currentTimeMillis()}")
        aliceDir.mkdirs()
        val aliceRepo = createTestRepository(aliceDir)
        aliceRepo.createIdentity("Alice")

        val oldPkHex = "1".repeat(64)
        val claim = aliceRepo.createRecoveryClaim(oldPkHex)

        // Bob creates a voucher
        val bobDir = File(context.cacheDir, "bob_voucher_${System.currentTimeMillis()}")
        bobDir.mkdirs()
        val bobRepo = createTestRepository(bobDir)
        bobRepo.createIdentity("Bob")

        try {
            val voucher = bobRepo.createRecoveryVoucher(claim.claimData)

            assertFalse(voucher.voucherData.isEmpty(), "Voucher data should not be empty")
            assertEquals(bobRepo.getPublicId(), voucher.voucherPublicKey, "Voucher should be from Bob")
        } finally {
            aliceDir.deleteRecursively()
            bobDir.deleteRecursively()
        }
    }

    // MARK: - Social Networks Tests

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
