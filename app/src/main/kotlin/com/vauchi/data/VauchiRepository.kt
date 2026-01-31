// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

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
// DONE: Device linking - getDevices(), generateDeviceLinkQr(), parseDeviceLinkQr(),
// deviceCount(), unlinkDevice(), isPrimaryDevice() methods implemented.

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
    private val preferences: VauchiPreferences
    private val keyStoreHelper = KeyStoreHelper()

    companion object {
        private const val KEY_ENCRYPTED_STORAGE_KEY = "encrypted_storage_key"
        private const val LEGACY_KEY_FILENAME = "storage.key"
    }

    init {
        val dataDir = context.filesDir.absolutePath
        prefs = context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        preferences = VauchiPreferences(prefs)
        val relayUrl = preferences.getRelayUrl()

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

    fun getRelayUrl(): String = preferences.getRelayUrl()

    fun setRelayUrl(url: String) = preferences.setRelayUrl(url)

    // Onboarding state management
    fun hasCompletedOnboarding(): Boolean = preferences.hasCompletedOnboarding()

    fun setOnboardingCompleted(completed: Boolean) = preferences.setOnboardingCompleted(completed)

    fun hasDismissedDemoContact(): Boolean = preferences.hasDismissedDemoContact()

    fun setDemoContactDismissed(dismissed: Boolean) = preferences.setDemoContactDismissed(dismissed)

    fun resetOnboarding() = preferences.resetOnboarding()

    // Accessibility settings
    fun getReduceMotion(): Boolean = preferences.getReduceMotion()

    fun setReduceMotion(enabled: Boolean) = preferences.setReduceMotion(enabled)

    fun getHighContrast(): Boolean = preferences.getHighContrast()

    fun setHighContrast(enabled: Boolean) = preferences.setHighContrast(enabled)

    fun getLargeTouchTargets(): Boolean = preferences.getLargeTouchTargets()

    fun setLargeTouchTargets(enabled: Boolean) = preferences.setLargeTouchTargets(enabled)

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

    // Device Linking operations
    // Based on: features/device_management.feature

    /**
     * Get list of linked devices.
     *
     * @return List of device info for all linked devices
     */
    fun getDevices() = vauchi.getDevices()

    /**
     * Generate a device link QR code for a new device to scan.
     *
     * @return QR code data with expiration info
     */
    fun generateDeviceLinkQr() = vauchi.generateDeviceLinkQr()

    /**
     * Parse a device link QR code scanned from another device.
     *
     * @param qrData The raw QR code data string
     * @return Parsed device link info
     */
    fun parseDeviceLinkQr(qrData: String) = vauchi.parseDeviceLinkQr(qrData)

    /**
     * Get the number of linked devices.
     *
     * @return Count of linked devices
     */
    fun deviceCount(): UInt = vauchi.deviceCount()

    /**
     * Unlink a device by its index in the device list.
     * Cannot unlink the current device.
     *
     * @param deviceIndex The index of the device to unlink
     * @return True if the device was successfully unlinked
     */
    fun unlinkDevice(deviceIndex: UInt): Boolean = vauchi.unlinkDevice(deviceIndex)

    /**
     * Check if this is the primary (first) device.
     *
     * @return True if this is the primary device
     */
    fun isPrimaryDevice(): Boolean = vauchi.isPrimaryDevice()
}
