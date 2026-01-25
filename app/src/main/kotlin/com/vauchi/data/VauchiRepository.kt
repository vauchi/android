package com.vauchi.data

// DONE: Field validation - validateField(), getFieldValidationStatus(),
// revokeFieldValidation(), listMyValidations(), hasValidatedField(),
// getFieldValidationCount() methods implemented.
//
// DONE: Content updates - isContentUpdatesSupported(), checkContentUpdates(),
// applyContentUpdates(), reloadSocialNetworks() methods implemented.
//
// DONE: Aha moments - hasSeenAhaMoment(), tryTriggerAhaMoment(),
// tryTriggerAhaMomentWithContext(), ahaMomentsSeenCount(), ahaMomentsTotalCount(),
// resetAhaMoments() methods implemented for progressive onboarding hints.
//
// DONE: Demo contact - implemented initDemoContactIfNeeded(), getDemoContact(),
// getDemoContactState(), isDemoUpdateAvailable(), triggerDemoUpdate(),
// dismissDemoContact(), autoRemoveDemoContact(), restoreDemoContact().
//
// DONE: Visibility labels - listLabels(), createLabel(), getLabel(), renameLabel(),
// deleteLabel(), addContactToLabel(), removeContactFromLabel(), getLabelsForContact(),
// setLabelFieldVisibility(), getSuggestedLabels().
//
// DONE: Certificate pinning - isCertificatePinningEnabled(), setPinnedCertificate()
// methods implemented. UI added to Settings under Security section.
//
// TODO(core-gap): Device linking - DevicesScreen is a stub. vauchi-mobile exposes
// MobileDeviceLinkData, MobileDeviceInfo, MobileDeviceLinkResult. Multi-device sync not functional.

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import uniffi.vauchi_mobile.MobileContactCard
import uniffi.vauchi_mobile.MobileExchangeData
import uniffi.vauchi_mobile.MobileFieldType
import uniffi.vauchi_mobile.MobileSyncResult
import uniffi.vauchi_mobile.VauchiMobile
import java.io.File

/**
 * Repository class wrapping VauchiMobile UniFFI bindings.
 * Uses Android KeyStore for secure storage key management.
 */
class VauchiRepository(context: Context) {
    private val vauchi: VauchiMobile
    private val prefs: SharedPreferences
    private val keyStoreHelper = KeyStoreHelper()

    companion object {
        private const val PREFS_NAME = "vauchi_settings"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_ENCRYPTED_STORAGE_KEY = "encrypted_storage_key"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_DEMO_CONTACT_DISMISSED = "demo_contact_dismissed"
        private const val DEFAULT_RELAY_URL = "wss://relay.vauchi.app"
        private const val LEGACY_KEY_FILENAME = "storage.key"

        // Accessibility settings keys
        private const val KEY_REDUCE_MOTION = "accessibility_reduce_motion"
        private const val KEY_HIGH_CONTRAST = "accessibility_high_contrast"
        private const val KEY_LARGE_TOUCH_TARGETS = "accessibility_large_touch_targets"
    }

    init {
        val dataDir = context.filesDir.absolutePath
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val relayUrl = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

        // Get or create storage key using Android KeyStore
        val storageKeyBytes = getOrCreateStorageKey(dataDir)

        // Initialize with secure key from KeyStore
        vauchi = VauchiMobile.newWithSecureKey(dataDir, relayUrl, storageKeyBytes)
    }

    /**
     * Get or create storage key from Android KeyStore.
     * Handles migration from legacy file-based key storage.
     */
    private fun getOrCreateStorageKey(dataDir: String): ByteArray {
        // Try to load encrypted key from preferences
        val encryptedKeyBase64 = prefs.getString(KEY_ENCRYPTED_STORAGE_KEY, null)
        if (encryptedKeyBase64 != null) {
            try {
                val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                return keyStoreHelper.decryptStorageKey(encryptedKey)
            } catch (e: Exception) {
                // Key decryption failed, might need to regenerate
                // Clear the invalid key
                prefs.edit().remove(KEY_ENCRYPTED_STORAGE_KEY).apply()
            }
        }

        // Check for legacy file-based key (migration scenario)
        val legacyKeyFile = File(dataDir, LEGACY_KEY_FILENAME)
        if (legacyKeyFile.exists()) {
            try {
                val legacyKey = legacyKeyFile.readBytes()
                if (legacyKey.size == 32) {
                    // Encrypt and save to preferences
                    val encryptedKey = keyStoreHelper.encryptStorageKey(legacyKey)
                    val encryptedBase64 = Base64.encodeToString(encryptedKey, Base64.DEFAULT)
                    prefs.edit().putString(KEY_ENCRYPTED_STORAGE_KEY, encryptedBase64).apply()

                    // Securely delete legacy file
                    legacyKeyFile.delete()

                    return legacyKey
                }
            } catch (e: Exception) {
                // Failed to migrate, generate new key
            }
        }

        // Generate new key, encrypt with KeyStore, and save
        val encryptedKey = keyStoreHelper.generateEncryptedStorageKey()
        val encryptedBase64 = Base64.encodeToString(encryptedKey, Base64.DEFAULT)
        prefs.edit().putString(KEY_ENCRYPTED_STORAGE_KEY, encryptedBase64).apply()

        // Decrypt to get the actual storage key bytes
        return keyStoreHelper.decryptStorageKey(encryptedKey)
    }

    /**
     * Export current storage key (for backup purposes only).
     * WARNING: Handle the returned data with extreme care.
     */
    fun exportStorageKey(): ByteArray = vauchi.exportStorageKey().map { it.toByte() }.toByteArray()

    fun getRelayUrl(): String = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(url: String) {
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
    }

    // Onboarding state management
    fun hasCompletedOnboarding(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun hasDismissedDemoContact(): Boolean = prefs.getBoolean(KEY_DEMO_CONTACT_DISMISSED, false)

    fun setDemoContactDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO_CONTACT_DISMISSED, dismissed).apply()
    }

    fun resetOnboarding() {
        prefs.edit()
            .remove(KEY_ONBOARDING_COMPLETED)
            .remove(KEY_DEMO_CONTACT_DISMISSED)
            .apply()
    }

    // Accessibility settings
    fun getReduceMotion(): Boolean = prefs.getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
    }

    fun getHighContrast(): Boolean = prefs.getBoolean(KEY_HIGH_CONTRAST, false)

    fun setHighContrast(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    fun getLargeTouchTargets(): Boolean = prefs.getBoolean(KEY_LARGE_TOUCH_TARGETS, false)

    fun setLargeTouchTargets(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LARGE_TOUCH_TARGETS, enabled).apply()
    }

    fun sync(): MobileSyncResult = vauchi.sync()

    fun hasIdentity(): Boolean = vauchi.hasIdentity()

    fun createIdentity(displayName: String) {
        vauchi.createIdentity(displayName)
    }

    fun getDisplayName(): String = vauchi.getDisplayName()

    fun getPublicId(): String = vauchi.getPublicId()

    fun getOwnCard(): MobileContactCard = vauchi.getOwnCard()

    fun addField(fieldType: MobileFieldType, label: String, value: String) {
        vauchi.addField(fieldType, label, value)
    }

    fun updateField(label: String, newValue: String) {
        vauchi.updateField(label, newValue)
    }

    fun removeField(label: String): Boolean = vauchi.removeField(label)

    fun generateExchangeQr(): MobileExchangeData = vauchi.generateExchangeQr()

    fun completeExchange(qrData: String) = vauchi.completeExchange(qrData)

    fun contactCount(): UInt = vauchi.contactCount()

    fun listContacts() = vauchi.listContacts()

    fun getContact(id: String) = vauchi.getContact(id)

    fun removeContact(id: String) = vauchi.removeContact(id)

    // Visibility operations
    fun hideFieldFromContact(contactId: String, fieldLabel: String) {
        vauchi.hideFieldFromContact(contactId, fieldLabel)
    }

    fun showFieldToContact(contactId: String, fieldLabel: String) {
        vauchi.showFieldToContact(contactId, fieldLabel)
    }

    fun isFieldVisibleToContact(contactId: String, fieldLabel: String): Boolean {
        return vauchi.isFieldVisibleToContact(contactId, fieldLabel)
    }

    // Visibility Labels operations
    // Based on: features/visibility_labels.feature

    /**
     * List all visibility labels
     */
    fun listLabels() = vauchi.listLabels()

    /**
     * Create a new visibility label
     */
    fun createLabel(name: String) = vauchi.createLabel(name)

    /**
     * Get label details by ID
     */
    fun getLabel(labelId: String) = vauchi.getLabel(labelId)

    /**
     * Rename a visibility label
     */
    fun renameLabel(labelId: String, newName: String) {
        vauchi.renameLabel(labelId, newName)
    }

    /**
     * Delete a visibility label
     */
    fun deleteLabel(labelId: String) {
        vauchi.deleteLabel(labelId)
    }

    /**
     * Add contact to a label
     */
    fun addContactToLabel(labelId: String, contactId: String) {
        vauchi.addContactToLabel(labelId, contactId)
    }

    /**
     * Remove contact from a label
     */
    fun removeContactFromLabel(labelId: String, contactId: String) {
        vauchi.removeContactFromLabel(labelId, contactId)
    }

    /**
     * Get all labels for a contact
     */
    fun getLabelsForContact(contactId: String) = vauchi.getLabelsForContact(contactId)

    /**
     * Set field visibility for a label
     */
    fun setLabelFieldVisibility(labelId: String, fieldId: String, visible: Boolean) {
        vauchi.setLabelFieldVisibility(labelId, fieldId, visible)
    }

    /**
     * Get suggested label names
     */
    fun getSuggestedLabels(): List<String> = vauchi.getSuggestedLabels()

    // Field Validation operations
    // Based on: features/field_validation.feature

    /**
     * Validate a contact's field
     */
    fun validateField(contactId: String, fieldId: String, fieldValue: String) =
        vauchi.validateField(contactId, fieldId, fieldValue)

    /**
     * Get validation status for a contact's field
     */
    fun getFieldValidationStatus(contactId: String, fieldId: String, fieldValue: String) =
        vauchi.getFieldValidationStatus(contactId, fieldId, fieldValue)

    /**
     * Revoke your validation of a contact's field
     */
    fun revokeFieldValidation(contactId: String, fieldId: String): Boolean =
        vauchi.revokeFieldValidation(contactId, fieldId)

    /**
     * List all validations you have made
     */
    fun listMyValidations() = vauchi.listMyValidations()

    /**
     * Check if you have validated a specific field
     */
    fun hasValidatedField(contactId: String, fieldId: String): Boolean =
        vauchi.hasValidatedField(contactId, fieldId)

    /**
     * Get the validation count for a field
     */
    fun getFieldValidationCount(contactId: String, fieldId: String): UInt =
        vauchi.getFieldValidationCount(contactId, fieldId)

    // Backup operations
    fun exportBackup(password: String): String = vauchi.exportBackup(password)

    fun importBackup(backupData: String, password: String) {
        vauchi.importBackup(backupData, password)
    }

    fun checkPasswordStrength(password: String) = uniffi.vauchi_mobile.checkPasswordStrength(password)

    // Social network operations
    fun listSocialNetworks() = vauchi.listSocialNetworks()

    fun searchSocialNetworks(query: String) = vauchi.searchSocialNetworks(query)

    fun getProfileUrl(networkId: String, username: String): String? =
        vauchi.getProfileUrl(networkId, username)

    // Content Updates operations
    // Based on: features/content_updates.feature

    /**
     * Check if content updates feature is supported
     */
    fun isContentUpdatesSupported(): Boolean = vauchi.isContentUpdatesSupported()

    /**
     * Check for available content updates
     */
    fun checkContentUpdates() = vauchi.checkContentUpdates()

    /**
     * Apply available content updates
     */
    fun applyContentUpdates() = vauchi.applyContentUpdates()

    /**
     * Reload social networks after content updates
     */
    fun reloadSocialNetworks() = vauchi.reloadSocialNetworks()

    // Aha Moments operations (Progressive Onboarding)

    /**
     * Check if user has seen a specific aha moment
     */
    fun hasSeenAhaMoment(momentType: uniffi.vauchi_mobile.MobileAhaMomentType): Boolean =
        vauchi.hasSeenAhaMoment(momentType)

    /**
     * Try to trigger an aha moment (returns null if already seen)
     */
    fun tryTriggerAhaMoment(momentType: uniffi.vauchi_mobile.MobileAhaMomentType) =
        vauchi.tryTriggerAhaMoment(momentType)

    /**
     * Try to trigger an aha moment with context (returns null if already seen)
     */
    fun tryTriggerAhaMomentWithContext(momentType: uniffi.vauchi_mobile.MobileAhaMomentType, context: String) =
        vauchi.tryTriggerAhaMomentWithContext(momentType, context)

    /**
     * Get count of seen aha moments
     */
    fun ahaMomentsSeenCount(): UInt = vauchi.ahaMomentsSeenCount()

    /**
     * Get total count of aha moments
     */
    fun ahaMomentsTotalCount(): UInt = vauchi.ahaMomentsTotalCount()

    /**
     * Reset all aha moments (for development/testing)
     */
    fun resetAhaMoments() = vauchi.resetAhaMoments()

    // Certificate Pinning operations

    /**
     * Check if certificate pinning is enabled
     */
    fun isCertificatePinningEnabled(): Boolean = vauchi.isCertificatePinningEnabled()

    /**
     * Set the pinned certificate for relay TLS connections
     * @param certPem Certificate in PEM format
     */
    fun setPinnedCertificate(certPem: String) = vauchi.setPinnedCertificate(certPem)

    // Verification operations
    fun verifyContact(id: String) = vauchi.verifyContact(id)

    fun getPublicKey(): String = vauchi.getPublicId()

    // Recovery operations
    fun createRecoveryClaim(oldPkHex: String) = vauchi.createRecoveryClaim(oldPkHex)

    fun parseRecoveryClaim(claimB64: String) = vauchi.parseRecoveryClaim(claimB64)

    fun createRecoveryVoucher(claimB64: String) = vauchi.createRecoveryVoucher(claimB64)

    fun addRecoveryVoucher(voucherB64: String) = vauchi.addRecoveryVoucher(voucherB64)

    fun getRecoveryStatus() = vauchi.getRecoveryStatus()

    fun getRecoveryProof(): String? = vauchi.getRecoveryProof()

    fun verifyRecoveryProof(proofB64: String) = vauchi.verifyRecoveryProof(proofB64)

    // Delivery status operations
    fun getAllDeliveryRecords() = vauchi.getAllDeliveryRecords()

    fun getDeliveryRecordsForContact(contactId: String) = vauchi.getDeliveryRecordsForContact(contactId)

    fun getDeliverySummary(messageId: String) = vauchi.getDeliverySummary(messageId)

    fun getDueRetries() = vauchi.getDueRetries()

    fun countFailedDeliveries(): UInt = vauchi.countFailedDeliveries()

    fun manualRetry(messageId: String): Boolean = vauchi.manualRetry(messageId)

    // Demo contact operations
    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     *
     * @return The demo contact if created, null if user has contacts or demo was dismissed
     */
    fun initDemoContactIfNeeded() = vauchi.initDemoContactIfNeeded()

    /**
     * Get the current demo contact if active.
     *
     * @return The demo contact if active, null otherwise
     */
    fun getDemoContact() = vauchi.getDemoContact()

    /**
     * Get the demo contact state.
     *
     * @return Current state of the demo contact
     */
    fun getDemoContactState() = vauchi.getDemoContactState()

    /**
     * Check if a demo update is available.
     *
     * @return True if an update is due (based on 2-hour interval)
     */
    fun isDemoUpdateAvailable(): Boolean = vauchi.isDemoUpdateAvailable()

    /**
     * Trigger a demo update and get the new content.
     *
     * @return Updated demo contact with new tip, null if demo not active
     */
    fun triggerDemoUpdate() = vauchi.triggerDemoUpdate()

    /**
     * Dismiss the demo contact manually.
     */
    fun dismissDemoContact() = vauchi.dismissDemoContact()

    /**
     * Auto-remove demo contact after first real exchange.
     * Call this after a successful contact exchange.
     *
     * @return True if demo was removed, false if it wasn't active
     */
    fun autoRemoveDemoContact(): Boolean = vauchi.autoRemoveDemoContact()

    /**
     * Restore the demo contact from Settings.
     *
     * @return The restored demo contact
     */
    fun restoreDemoContact() = vauchi.restoreDemoContact()
}
