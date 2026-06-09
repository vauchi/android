// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import app.vauchi.util.LocalizationManager
import app.vauchi.util.ThemeManager
import app.vauchi.util.pushDeviceCapabilities
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.VauchiPlatform
import java.io.File

/**
 * Exchange data for QR display (replaces deleted MobileExchangeData).
 */
data class ExchangeData(
    val qrData: String,
    val publicId: String,
    val expiresAt: ULong,
    val audioChallenge: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExchangeData) return false
        return qrData == other.qrData && publicId == other.publicId &&
            expiresAt == other.expiresAt && audioChallenge.contentEquals(other.audioChallenge)
    }

    override fun hashCode(): Int {
        var result = qrData.hashCode()
        result = 31 * result + publicId.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + (audioChallenge?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Repository class wrapping VauchiPlatform UniFFI bindings.
 * Uses Android KeyStore for secure storage key management.
 */
class VauchiRepository(
    private val context: Context,
    private val keyStoreHelper: StorageKeyProvider = KeyStoreHelper(),
) {
    private lateinit var _vauchi: VauchiPlatform
    private lateinit var _appEngine: PlatformAppEngine
    private var initialized = false
    private val prefs: SharedPreferences
    private val preferences: VauchiPreferences

    companion object {
        private const val KEY_ENCRYPTED_STORAGE_KEY = "encrypted_storage_key"

        // S4 of `2026-05-16-settings-storage-by-sensitivity`: render-
        // context state (theme + locale) lives in the same OS-native
        // SharedPreferences files [ThemeManager] / [LocalizationManager]
        // already use. The migration below copies any existing vault row
        // into these files exactly once per upgrade.

        /**
         * Extract the 16-byte audio challenge from a wb:// QR data string.
         * QR binary layout: [MAGIC(4)][version(1)][pubkey(32)][exchkey(32)][token(32)][audio_challenge(16)][...]
         * Audio challenge = bytes 101..117 after base64 decode.
         */
        fun extractAudioChallenge(qrData: String): ByteArray? {
            val b64 = qrData.removePrefix("wb://")
            val bytes =
                try {
                    Base64.decode(b64, Base64.NO_WRAP)
                } catch (_: Exception) {
                    return null
                }
            if (bytes.size < 117) return null
            return bytes.sliceArray(101 until 117)
        }
    }

    init {
        prefs = context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        preferences = VauchiPreferences(prefs)

        // Pre-check: device must have a secure lock screen for KeyStore operations.
        // This is a fast check (no KeyStore access). The actual KeyStore entry is
        // created lazily in platform() on first use — NOT here.
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isDeviceSecure) {
            throw DeviceNotSecureException(
                "A secure lock screen (PIN, pattern, or biometric) is required to protect your data. " +
                    "Please set one up in your device Settings.",
            )
        }
    }

    /**
     * Lazily initialize the VauchiPlatform on first use.
     *
     * This defers KeyStore entry creation from app startup to the first actual
     * operation (e.g., createIdentity, hasIdentity). This prevents the bug where
     * force-stopping during onboarding left a KeyStore entry that trapped users
     * in an "Authentication Required" loop.
     */
    @Synchronized
    private fun platform(): VauchiPlatform {
        if (!initialized) {
            val dataDir = context.filesDir.absolutePath
            val relayUrl = preferences.getRelayUrl()
            val storageKeyBytes = getOrCreateStorageKey(dataDir)
            _vauchi = VauchiPlatform.newWithSecureKey(dataDir, relayUrl, storageKeyBytes)
            _vauchi.setPlatformKeychain(PlatformKeychainBridge(context))
            _appEngine = PlatformAppEngine(dataDir, relayUrl, storageKeyBytes)
            // B7 Phase 2: also wire the keychain to PlatformAppEngine so the
            // core-driven shred DomainCommands (SoftShred / CancelShred /
            // HardShred / PanicShred) can reach the platform keychain. The
            // VauchiPlatform slot above stays for `widget_panic_shred`.
            _appEngine.setPlatformKeychain(PlatformKeychainBridge(context))
            initialized = true

            ThemeManager.getInstance(context).attachAppEngine(_appEngine)
            LocalizationManager.getInstance(context).attachAppEngine(_appEngine)

            // Report this device's exchange-relevant hardware to core so the
            // Exchange mode picker offers only modes the device can perform.
            // Without this push core falls back to `DeviceCapabilities::default()`
            // (all-false) — see `2026-05-23-exchange-capabilities-frontend-gap`.
            pushDeviceCapabilities(context, _appEngine)
        }
        return _vauchi
    }

    /**
     * Shared PlatformAppEngine for core-driven screen rendering.
     * Created alongside VauchiPlatform using the same credentials —
     * single DB connection, shared cache across all screens.
     * Call [platform] first to ensure initialization.
     */
    val appEngine: PlatformAppEngine
        get() {
            platform() // ensure initialized
            return _appEngine
        }

    /**
     * Get or create storage key from Android KeyStore.
     *
     * @throws KeyInvalidatedRecoveryRequired when the master key is gone
     *   (KPIE / AEAD-bad-tag) and the local encrypted state has been
     *   wiped. The caller routes the user to a recovery screen.
     */
    private fun getOrCreateStorageKey(dataDir: String): ByteArray {
        val hadData = prefs.getString(KEY_ENCRYPTED_STORAGE_KEY, null) != null

        try {
            // Try to load encrypted key from preferences
            val encryptedKeyBase64 = prefs.getString(KEY_ENCRYPTED_STORAGE_KEY, null)
            if (encryptedKeyBase64 != null) {
                try {
                    val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                    return keyStoreHelper.decryptStorageKey(encryptedKey)
                } catch (e: AuthenticationRequiredException) {
                    // Device must be unlocked — propagate to caller
                    throw e
                } catch (e: KeyInvalidatedException) {
                    // Master key gone or stored ciphertext no longer
                    // authenticates — handled by the outer catch below
                    // so the wipe runs once at the function boundary.
                    throw e
                } catch (e: Exception) {
                    // Other decryption failures: clear the invalid blob
                    // and fall through to regenerate.
                    prefs.edit().remove(KEY_ENCRYPTED_STORAGE_KEY).apply()
                }
            }

            // Generate new key, encrypt with KeyStore, and save
            val encryptedKey = keyStoreHelper.generateEncryptedStorageKey()
            val encryptedBase64 = Base64.encodeToString(encryptedKey, Base64.DEFAULT)
            prefs.edit().putString(KEY_ENCRYPTED_STORAGE_KEY, encryptedBase64).apply()

            // Decrypt to get the actual storage key bytes
            return keyStoreHelper.decryptStorageKey(encryptedKey)
        } catch (e: KeyInvalidatedException) {
            Log.e(
                "VauchiRepository",
                "Storage master key invalidated (${e.cause?.javaClass?.simpleName}); " +
                    "wiping encrypted state. hadData=$hadData",
            )
            wipeEncryptedStorageState(dataDir)
            throw KeyInvalidatedRecoveryRequired(hadData = hadData, cause = e)
        }
    }

    /**
     * Removes every artifact that depends on the (now-gone) master key:
     * the SQLCipher database files, the encrypted-storage-key blob in
     * SharedPreferences, and the KeyStore alias itself. Idempotent —
     * safe to call when files don't exist. Used for recovery when the
     * master key is invalidated.
     */
    private fun wipeEncryptedStorageState(dataDir: String) {
        for (suffix in arrayOf("", "-shm", "-wal", "-journal")) {
            val f = File(dataDir, "vauchi.db$suffix")
            if (f.exists() && !f.delete()) {
                Log.w("VauchiRepository", "Failed to delete ${f.path}")
            }
        }
        prefs.edit().remove(KEY_ENCRYPTED_STORAGE_KEY).apply()
        try {
            keyStoreHelper.deleteMasterKey()
        } catch (e: Exception) {
            Log.w("VauchiRepository", "deleteMasterKey() failed during wipe: ${e.javaClass.simpleName}")
        }
    }

    fun getRelayUrl(): String = preferences.getRelayUrl()

    fun setRelayUrl(url: String) = preferences.setRelayUrl(url)

    fun hasCompletedOnboarding(): Boolean = preferences.hasCompletedOnboarding()

    fun setOnboardingCompleted(completed: Boolean) = preferences.setOnboardingCompleted(completed)

    fun hasDismissedDemoContact(): Boolean = preferences.hasDismissedDemoContact()

    fun setDemoContactDismissed(dismissed: Boolean) = preferences.setDemoContactDismissed(dismissed)

    fun resetOnboarding() = preferences.resetOnboarding()

    fun getReduceMotion(): Boolean = preferences.getReduceMotion()

    fun setReduceMotion(enabled: Boolean) = preferences.setReduceMotion(enabled)

    fun getHighContrast(): Boolean = preferences.getHighContrast()

    fun setHighContrast(enabled: Boolean) = preferences.setHighContrast(enabled)

    fun getLargeTouchTargets(): Boolean = preferences.getLargeTouchTargets()

    fun setLargeTouchTargets(enabled: Boolean) = preferences.setLargeTouchTargets(enabled)

    fun sync(): MobileSyncResult = platform().sync()

    fun pendingUpdateCount(): UInt {
        platform() // ensure lazy init
        return _appEngine.pendingUpdateCount()
    }

    fun hasIdentity(): Boolean {
        platform() // ensure initialized
        return appEngine.hasIdentity()
    }

    fun createIdentity(displayName: String) {
        platform() // ensure initialized
        appEngine.createIdentity(displayName)
    }

    fun getDisplayName(): String {
        platform() // ensure initialized
        return appEngine.getDisplayName()
    }

    fun setDisplayName(name: String) {
        platform() // ensure initialized
        appEngine.setDisplayName(name)
    }

    fun getPublicId(): String {
        platform() // ensure initialized
        return appEngine.getPublicId()
    }

    fun getOwnCard(): MobileContactCard {
        platform() // ensure initialized
        return appEngine.getOwnCard()
    }

    fun addField(
        fieldType: MobileFieldType,
        label: String,
        value: String,
    ) {
        platform() // ensure initialized
        appEngine.addField(fieldType, label, value)
    }

    fun updateField(
        label: String,
        newValue: String,
    ) {
        platform() // ensure initialized
        appEngine.updateField(label, newValue)
    }

    fun removeField(label: String): Boolean {
        platform() // ensure initialized
        return appEngine.removeField(label)
    }

    fun contactCount(): UInt = appEngine.contactCount()

    fun listContactsPaginated(
        offset: UInt,
        limit: UInt,
    ) = appEngine.listContactsPaginated(offset, limit)

    fun searchContacts(query: String) = appEngine.searchContacts(query)

    fun getContact(id: String) = appEngine.getContact(id)

    fun removeContact(id: String) = appEngine.removeContact(id)

    fun softDeleteImportedContact(id: String) = appEngine.softDeleteImportedContact(id)

    fun archiveContact(id: String) = appEngine.archiveContact(id)

    fun unarchiveContact(id: String) = appEngine.unarchiveContact(id)

    /**
     * Returns the footer-button action id (`"delete_contact"` or
     * `"archive_contact"`) for the given contact. Views dispatch on
     * the returned id so they never branch on the imported-vs-exchanged
     * distinction in the view layer (§1A pure-renderer rule).
     */
    fun contactDetailFooterActionId(contactId: String) = appEngine.contactDetailFooterActionId(contactId)

    /**
     * G4 (ADR-021/043): typed contact-detail view-state — frontends
     * iterate `actions`/`badges`/`banners` instead of branching on
     * raw MobileContact flags. Closes the iOS/Android Verify-button
     * divergence (audit V4).
     */
    fun contactDetailViewState(contactId: String) = appEngine.contactDetailViewState(contactId)

    fun listArchivedContacts() = appEngine.listArchivedContacts()

    fun hideFieldFromContact(
        contactId: String,
        fieldLabel: String,
    ) {
        appEngine.hideFieldFromContact(contactId, fieldLabel)
    }

    fun showFieldToContact(
        contactId: String,
        fieldLabel: String,
    ) {
        appEngine.showFieldToContact(contactId, fieldLabel)
    }

    fun isFieldVisibleToContact(
        contactId: String,
        fieldLabel: String,
    ): Boolean = appEngine.isFieldVisibleToContact(contactId, fieldLabel)

    // Based on: features/visibility_labels.feature

    /**
     * List all visibility labels
     */
    fun listLabels() = appEngine.listLabels()

    /**
     * Create a new visibility label
     */
    fun createLabel(name: String) = appEngine.createLabel(name)

    /**
     * Get label details by ID
     */
    fun getLabel(labelId: String) = appEngine.getLabel(labelId)

    /**
     * Rename a visibility label
     */
    fun renameLabel(
        labelId: String,
        newName: String,
    ) {
        appEngine.renameLabel(labelId, newName)
    }

    /**
     * Delete a visibility label
     */
    fun deleteLabel(labelId: String) {
        appEngine.deleteLabel(labelId)
    }

    /**
     * Get suggested label names.
     *
     * Non-throwing wrapper that returns `[]` on failure — `getSuggestedLabels`
     * is a non-essential UI hint, so dispatch errors silently degrade rather
     * than propagate. The legacy `platform().getSuggestedLabels()` was likewise
     * non-throwing on the FFI surface; we preserve that shape.
     */
    fun getSuggestedLabels(): List<String> = runCatching { appEngine.getSuggestedLabels() }.getOrDefault(emptyList())

    fun exportBackup(password: String): String {
        platform() // ensure initialized
        return appEngine.exportBackup(password)
    }

    fun importBackup(
        backupData: String,
        password: String,
    ) {
        platform() // ensure initialized
        appEngine.importBackup(backupData, password)
    }

    // TODO: wire once export_full_backup is exported via UniFFI
    fun exportFullBackup(password: String): String {
        platform() // ensure initialized
        return appEngine.exportBackup(password)
    }

    fun importFullBackup(
        backupData: String,
        password: String,
    ) {
        platform() // ensure initialized
        appEngine.importBackup(backupData, password)
    }

    // Non-throwing wrappers that silently degrade on dispatch failure —
    // callers treat social-networks data as a UI hint, so the legacy
    // non-throwing FFI shape is preserved by swallowing
    // `dispatchDomainCommand` errors. Same convention applies to the
    // Content Updates / Aha Moments / Cert Pinning wrappers below.
    fun listSocialNetworks(): List<uniffi.vauchi_platform.MobileSocialNetwork> =
        runCatching { appEngine.listSocialNetworks() }.getOrDefault(emptyList())

    fun searchSocialNetworks(query: String): List<uniffi.vauchi_platform.MobileSocialNetwork> =
        runCatching { appEngine.searchSocialNetworks(query) }.getOrDefault(emptyList())

    fun getProfileUrl(
        networkId: String,
        username: String,
    ): String? = runCatching { appEngine.getProfileUrl(networkId, username) }.getOrNull()

    // Based on: features/content_updates.feature

    /**
     * Check if content updates feature is supported
     */
    fun isContentUpdatesSupported(): Boolean = runCatching { appEngine.isContentUpdatesSupported() }.getOrDefault(false)

    /**
     * Check for available content updates
     */
    fun checkContentUpdates(): uniffi.vauchi_platform.MobileUpdateStatus =
        runCatching { appEngine.checkContentUpdates() }
            .getOrDefault(uniffi.vauchi_platform.MobileUpdateStatus.UpToDate)

    /**
     * Apply available content updates
     */
    fun applyContentUpdates(): uniffi.vauchi_platform.MobileApplyResult =
        runCatching { appEngine.applyContentUpdates() }
            .getOrDefault(uniffi.vauchi_platform.MobileApplyResult.Error("Dispatch failed"))

    /**
     * Reload social networks after content updates
     */
    fun reloadSocialNetworks(): List<uniffi.vauchi_platform.MobileSocialNetwork> =
        runCatching { appEngine.reloadSocialNetworks() }.getOrDefault(emptyList())

    /**
     * Check if certificate pinning is enabled
     */
    fun isCertificatePinningEnabled(): Boolean = runCatching { appEngine.isCertificatePinningEnabled() }.getOrDefault(false)

    /**
     * Set the pinned certificate for relay TLS connections
     * @param certPem Certificate in PEM format
     */
    fun setPinnedCertificate(certPem: String) {
        runCatching { appEngine.setPinnedCertificate(certPem) }
    }

    /**
     * Authenticate with app password. Returns the auth mode (Normal or Duress).
     * Core sets internal auth_mode which controls contact visibility (decoy vs real).
     */
    fun authenticate(password: String): uniffi.vauchi_platform.MobileAuthMode = appEngine.authenticate(password)

    /**
     * Set up app password (prerequisite for duress PIN).
     */
    fun setupAppPassword(password: String) {
        appEngine.setupAppPassword(password)
    }

    /**
     * Check if app password is configured.
     */
    fun isPasswordEnabled(): Boolean = appEngine.isPasswordEnabled()

    /**
     * Check if duress PIN is enabled
     */
    fun isDuressEnabled(): Boolean = appEngine.isDuressEnabled()

    /**
     * Set up duress PIN (requires app password to be set first)
     */
    fun setupDuressPassword(duressPassword: String) {
        appEngine.setupDuressPassword(duressPassword)
    }

    /**
     * Disable duress PIN
     */
    fun disableDuress() {
        appEngine.disableDuress()
    }

    fun hideContact(contactId: String) {
        appEngine.hideContact(contactId)
    }

    fun unhideContact(contactId: String) {
        appEngine.unhideContact(contactId)
    }

    fun listHiddenContacts(): List<uniffi.vauchi_platform.MobileContact> = appEngine.listHiddenContacts()

    fun configureDuressAlerts(
        contactIds: List<String>,
        message: String,
    ) {
        appEngine.configureDuressAlerts(contactIds, message)
    }

    fun getDuressSettings(): uniffi.vauchi_platform.MobileDuressSettings? = appEngine.getDuressSettings()

    fun setContactNote(
        contactId: String,
        note: String,
    ) {
        appEngine.setContactNote(contactId, note)
    }

    fun getContactNote(contactId: String): String? = appEngine.getContactNote(contactId)

    fun deleteContactNote(contactId: String) {
        appEngine.deleteContactNote(contactId)
    }

    fun setContactFieldNote(
        contactId: String,
        fieldId: String,
        note: String,
    ) {
        appEngine.setContactFieldNote(contactId, fieldId, note)
    }

    fun getContactFieldNotes(contactId: String): List<uniffi.vauchi_platform.MobileFieldNote> = appEngine.getContactFieldNotes(contactId)

    fun deleteContactFieldNote(
        contactId: String,
        fieldId: String,
    ) {
        appEngine.deleteContactFieldNote(contactId, fieldId)
    }

    fun setProposalTrusted(
        contactId: String,
        trusted: Boolean,
    ) {
        appEngine.setProposalTrusted(contactId, trusted)
    }

    fun verifyContact(id: String) = appEngine.verifyContact(id)

    fun getOwnFingerprint(): String = appEngine.getOwnFingerprint()

    fun trustContactForRecovery(id: String) = appEngine.trustContactForRecovery(id)

    fun untrustContactForRecovery(id: String) = appEngine.untrustContactForRecovery(id)

    fun verifyRecoveryProof(proofB64: String) = appEngine.verifyRecoveryProof(proofB64)

    fun getDeliveryRecordsForContact(contactId: String) = appEngine.getDeliveryRecordsForContact(contactId)

    fun getDeliverySummary(messageId: String) = appEngine.getDeliverySummary(messageId)

    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     *
     * @return The demo contact if created, null if user has contacts or demo was dismissed
     */
    fun initDemoContactIfNeeded(): uniffi.vauchi_platform.MobileDemoContact? {
        platform() // ensure initialized
        return appEngine.initDemoContactIfNeeded()
    }

    /**
     * Get the current demo contact if active.
     *
     * @return The demo contact if active, null otherwise
     */
    fun getDemoContact(): uniffi.vauchi_platform.MobileDemoContact? {
        platform() // ensure initialized
        return appEngine.getDemoContact()
    }

    /**
     * Get the demo contact state.
     *
     * @return Current state of the demo contact
     */
    fun getDemoContactState(): uniffi.vauchi_platform.MobileDemoContactState =
        runCatching { appEngine.getDemoContactState() }.getOrDefault(
            uniffi.vauchi_platform.MobileDemoContactState(
                isActive = false,
                wasDismissed = false,
                autoRemoved = false,
                updateCount = 0u,
            ),
        )

    /**
     * Check if a demo update is available.
     *
     * @return True if an update is due (based on 2-hour interval)
     */
    fun isDemoUpdateAvailable(): Boolean = runCatching { appEngine.isDemoUpdateAvailable() }.getOrDefault(false)

    /**
     * Trigger a demo update and get the new content.
     *
     * @return Updated demo contact with new tip, null if demo not active
     */
    fun triggerDemoUpdate(): uniffi.vauchi_platform.MobileDemoContact? {
        platform() // ensure initialized
        return appEngine.triggerDemoUpdate()
    }

    /**
     * Dismiss the demo contact manually.
     */
    fun dismissDemoContact() {
        platform() // ensure initialized
        appEngine.dismissDemoContact()
    }

    /**
     * Auto-remove demo contact after first real exchange.
     * Call this after a successful contact exchange.
     *
     * @return True if demo was removed, false if it wasn't active
     */
    fun autoRemoveDemoContact(): Boolean {
        platform() // ensure initialized
        return appEngine.autoRemoveDemoContact()
    }

    /**
     * Restore the demo contact from Settings.
     *
     * @return The restored demo contact
     */
    fun restoreDemoContact(): uniffi.vauchi_platform.MobileDemoContact? {
        platform() // ensure initialized
        return appEngine.restoreDemoContact()
    }

    /**
     * Create a new device-link orchestration session (Phase 1: initiator only).
     * Core's cycle thread owns QR generation, request listening, state transitions,
     * and persistence. Frontend wires a `DeviceLinkSessionListener` for events.
     */

    /**
     * Send a device link request via the relay and wait for a response.
     */

    // Based on: features/privacy_compliance.feature

    /**
     * Export all user data as GDPR-compliant JSON.
     *
     * @return GDPR export with JSON data, timestamp, and version
     */
    fun exportGdprData() = appEngine.exportGdprData()

    /**
     * Schedule account deletion with 7-day grace period.
     *
     * @return Deletion info with state and timing
     */
    fun scheduleIdentityDeletion() = appEngine.scheduleIdentityDeletion()

    /**
     * Cancel a scheduled account deletion.
     */
    fun cancelIdentityDeletion() = appEngine.cancelIdentityDeletion()

    /**
     * Get current deletion state.
     *
     * @return Current deletion info
     */
    fun getDeletionState() = appEngine.getDeletionState()

    /**
     * Grant consent for a specific type.
     *
     * @param consentType The type of consent to grant
     */
    fun grantConsent(consentType: uniffi.vauchi_platform.MobileConsentType) = appEngine.grantConsent(consentType)

    /**
     * Revoke consent for a specific type.
     *
     * @param consentType The type of consent to revoke
     */
    fun revokeConsent(consentType: uniffi.vauchi_platform.MobileConsentType) = appEngine.revokeConsent(consentType)

    /**
     * Get all consent records.
     *
     * @return List of all consent records
     */
    fun getConsentRecords() = appEngine.getConsentRecords()

    /**
     * Handle app backgrounded event (C1 auto-lock).
     */
    fun handleAppBackgrounded(): String? =
        try {
            appEngine.handleAppBackgrounded()
        } catch (e: Exception) {
            Log.e("VauchiRepository", "handleAppBackgrounded failed", e)
            null
        }

    /**
     * Poll for OS notifications produced by the app engine (E).
     */
    fun pollNotifications(): List<uniffi.vauchi_platform.MobilePendingNotification> =
        try {
            appEngine.pollNotifications()
        } catch (e: Exception) {
            Log.e("VauchiRepository", "pollNotifications failed", e)
            emptyList()
        }
}
