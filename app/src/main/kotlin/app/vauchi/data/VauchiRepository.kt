// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

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
// deleteLabel(), addContactToGroup(), removeContactFromGroup(), getGroupsForContact(),
// setGroupFieldVisibility(), getSuggestedLabels().
//
// DONE: Certificate pinning - isCertificatePinningEnabled(), setPinnedCertificate()
// methods implemented. UI added to Settings under Security section.
//
// DONE: Device linking - getDevices(), generateDeviceLinkQr(), parseDeviceLinkQr(),
// deviceCount(), unlinkDevice(), isPrimaryDevice() methods implemented.

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileExchangeResult
import uniffi.vauchi_platform.MobileExchangeSession
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileMultiStageSession
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.PlatformAppEngine
import uniffi.vauchi_platform.VauchiPlatform

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
 * Holds both the display data and the live session for a single exchange.
 * The session MUST be reused for processQr/finalize — creating a new session
 * generates different ephemeral keys and breaks key agreement.
 */
data class ExchangeSessionData(
    val exchangeData: ExchangeData,
    val session: MobileExchangeSession,
)

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
            initialized = true
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
     */
    private fun getOrCreateStorageKey(dataDir: String): ByteArray {
        // Try to load encrypted key from preferences
        val encryptedKeyBase64 = prefs.getString(KEY_ENCRYPTED_STORAGE_KEY, null)
        if (encryptedKeyBase64 != null) {
            try {
                val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                return keyStoreHelper.decryptStorageKey(encryptedKey)
            } catch (e: AuthenticationRequiredException) {
                // Device must be unlocked — propagate to caller
                throw e
            } catch (e: Exception) {
                // Key decryption failed, might need to regenerate
                // Clear the invalid key
                prefs.edit().remove(KEY_ENCRYPTED_STORAGE_KEY).apply()
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
    fun exportStorageKey(): ByteArray = platform().exportStorageKey().map { it.toByte() }.toByteArray()

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

    fun sync(): MobileSyncResult = platform().sync()

    // Privacy toggles

    fun isDeliveryReceiptsEnabled(): Boolean = platform().isDeliveryReceiptsEnabled()

    fun setDeliveryReceiptsEnabled(enabled: Boolean) = platform().setDeliveryReceiptsEnabled(enabled)

    fun isSuppressPresenceEnabled(): Boolean = platform().isSuppressPresenceEnabled()

    fun setSuppressPresenceEnabled(enabled: Boolean) = platform().setSuppressPresenceEnabled(enabled)

    fun getSyncStatus(): uniffi.vauchi_platform.MobileSyncStatus = platform().getSyncStatus()

    fun pendingUpdateCount(): UInt = platform().pendingUpdateCount()

    fun hasIdentity(): Boolean = platform().hasIdentity()

    fun createIdentity(displayName: String) {
        platform().createIdentity(displayName)
    }

    fun getDisplayName(): String = platform().getDisplayName()

    fun setDisplayName(name: String) = platform().setDisplayName(name)

    fun getPublicId(): String = platform().getPublicId()

    fun getOwnCard(): MobileContactCard = platform().getOwnCard()

    fun addField(
        fieldType: MobileFieldType,
        label: String,
        value: String,
    ) {
        platform().addField(fieldType, label, value)
    }

    fun updateField(
        label: String,
        newValue: String,
    ) {
        platform().updateField(label, newValue)
    }

    fun removeField(label: String): Boolean = platform().removeField(label)

    /**
     * Generate exchange QR data AND return the live session.
     * The caller MUST hold onto the session and pass it to [finalizeExchange]
     * — creating a new session generates different ephemeral keys.
     */
    fun generateExchangeQrWithSession(): ExchangeSessionData {
        val session = platform().createQrExchangeManual()
        val qrData = session.generateQr()
        val expiresAt = System.currentTimeMillis() / 1000 + 300 // 5 minutes
        val data =
            ExchangeData(
                qrData = qrData,
                publicId = platform().getPublicId(),
                expiresAt = expiresAt.toULong(),
                audioChallenge = extractAudioChallenge(qrData),
            )
        return ExchangeSessionData(exchangeData = data, session = session)
    }

    /**
     * Finalize an exchange using the SAME session that generated the QR.
     * The session must have already been driven through processQr → confirmProximity →
     * theyScannedOurQr → performKeyAgreement → completeCardExchange.
     */
    fun finalizeExchange(session: MobileExchangeSession): MobileExchangeResult = platform().finalizeExchange(session)

    /** Create a multi-stage exchange session with the real identity and card data. */
    fun createMultistageSession(): MobileMultiStageSession = platform().createMultistageSession()

    /** Create an NFC initiator (reader) handshake session. */
    fun createNfcInitiator() = platform().createNfcInitiator()

    /** Create an NFC responder (HCE) handshake session. */
    fun createNfcResponder() = platform().createNfcResponder()

    fun contactCount(): UInt = platform().contactCount()

    fun listContacts() = platform().listContacts()

    fun listContactsPaginated(
        offset: UInt,
        limit: UInt,
    ) = platform().listContactsPaginated(offset, limit)

    fun searchContacts(query: String) = platform().searchContacts(query)

    fun getContact(id: String) = platform().getContact(id)

    fun removeContact(id: String) = platform().removeContact(id)

    // Contact lifecycle (reversible deletion + archival)

    fun softDeleteImportedContact(id: String) = platform().softDeleteImportedContact(id)

    fun undoDeleteImportedContact(id: String) = platform().undoDeleteImportedContact(id)

    fun hardDeleteImportedContact(id: String) = platform().hardDeleteImportedContact(id)

    fun archiveContact(id: String) = platform().archiveContact(id)

    fun unarchiveContact(id: String) = platform().unarchiveContact(id)

    /**
     * Returns the footer-button action id (`"delete_contact"` or
     * `"archive_contact"`) for the given contact. Views dispatch on
     * the returned id so they never branch on the imported-vs-exchanged
     * distinction in the view layer (§1A pure-renderer rule).
     */
    fun contactDetailFooterActionId(contactId: String) = platform().contactDetailFooterActionId(contactId)

    /**
     * G4 (ADR-021/043): typed contact-detail view-state — frontends
     * iterate `actions`/`badges`/`banners` instead of branching on
     * raw MobileContact flags. Closes the iOS/Android Verify-button
     * divergence (audit V4).
     */
    fun contactDetailViewState(contactId: String) = platform().contactDetailViewState(contactId)

    fun listArchivedContacts() = platform().listArchivedContacts()

    fun importContactsFromVcf(data: ByteArray) = platform().importContactsFromVcf(data)

    // Visibility operations
    fun hideFieldFromContact(
        contactId: String,
        fieldLabel: String,
    ) {
        platform().hideFieldFromContact(contactId, fieldLabel)
    }

    fun showFieldToContact(
        contactId: String,
        fieldLabel: String,
    ) {
        platform().showFieldToContact(contactId, fieldLabel)
    }

    fun isFieldVisibleToContact(
        contactId: String,
        fieldLabel: String,
    ): Boolean = platform().isFieldVisibleToContact(contactId, fieldLabel)

    // Visibility Labels operations
    // Based on: features/visibility_labels.feature

    /**
     * List all visibility labels
     */
    fun listLabels() = platform().listLabels()

    /**
     * Create a new visibility label
     */
    fun createLabel(name: String) = platform().createLabel(name)

    /**
     * Get label details by ID
     */
    fun getLabel(labelId: String) = platform().getLabel(labelId)

    /**
     * Rename a visibility label
     */
    fun renameLabel(
        labelId: String,
        newName: String,
    ) {
        platform().renameLabel(labelId, newName)
    }

    /**
     * Delete a visibility label
     */
    fun deleteLabel(labelId: String) {
        platform().deleteLabel(labelId)
    }

    fun addContactToLabel(
        labelId: String,
        contactId: String,
    ) {
        platform().addContactToGroup(labelId, contactId)
    }

    fun removeContactFromLabel(
        labelId: String,
        contactId: String,
    ) {
        platform().removeContactFromGroup(labelId, contactId)
    }

    fun getLabelsForContact(contactId: String): List<uniffi.vauchi_platform.MobileVisibilityLabel> =
        platform().getGroupsForContact(contactId)

    fun setLabelFieldVisibility(
        labelId: String,
        fieldId: String,
        visible: Boolean,
    ) {
        platform().setGroupFieldVisibility(labelId, fieldId, visible)
    }

    /**
     * Get suggested label names
     */
    fun getSuggestedLabels(): List<String> = platform().getSuggestedLabels()

    // Backup operations
    fun exportBackup(password: String): String = platform().exportBackup(password)

    fun importBackup(
        backupData: String,
        password: String,
    ) {
        platform().importBackup(backupData, password)
    }

    // Full backup operations (identity + contacts + own card + labels)
    // TODO: wire once export_full_backup is exported via UniFFI
    fun exportFullBackup(password: String): String = platform().exportBackup(password)

    fun importFullBackup(
        backupData: String,
        password: String,
    ) {
        platform().importBackup(backupData, password)
    }

    // Social network operations
    fun listSocialNetworks() = platform().listSocialNetworks()

    fun searchSocialNetworks(query: String) = platform().searchSocialNetworks(query)

    fun getProfileUrl(
        networkId: String,
        username: String,
    ): String? = platform().getProfileUrl(networkId, username)

    // Content Updates operations
    // Based on: features/content_updates.feature

    /**
     * Check if content updates feature is supported
     */
    fun isContentUpdatesSupported(): Boolean = platform().isContentUpdatesSupported()

    /**
     * Check for available content updates
     */
    fun checkContentUpdates() = platform().checkContentUpdates()

    /**
     * Apply available content updates
     */
    fun applyContentUpdates() = platform().applyContentUpdates()

    /**
     * Reload social networks after content updates
     */
    fun reloadSocialNetworks() = platform().reloadSocialNetworks()

    // Aha Moments operations (Progressive Onboarding)

    /**
     * Check if user has seen a specific aha moment
     */
    fun hasSeenAhaMoment(momentType: uniffi.vauchi_platform.MobileAhaMomentType): Boolean = platform().hasSeenAhaMoment(momentType)

    /**
     * Try to trigger an aha moment (returns null if already seen)
     */
    fun tryTriggerAhaMoment(momentType: uniffi.vauchi_platform.MobileAhaMomentType) = platform().tryTriggerAhaMoment(momentType)

    /**
     * Try to trigger an aha moment with context (returns null if already seen)
     */
    fun tryTriggerAhaMomentWithContext(
        momentType: uniffi.vauchi_platform.MobileAhaMomentType,
        context: String,
    ) = platform().tryTriggerAhaMomentWithContext(momentType, context)

    /**
     * Get count of seen aha moments
     */
    fun ahaMomentsSeenCount(): UInt = platform().ahaMomentsSeenCount()

    /**
     * Get total count of aha moments
     */
    fun ahaMomentsTotalCount(): UInt = platform().ahaMomentsTotalCount()

    /**
     * Reset all aha moments (for development/testing)
     */
    fun resetAhaMoments() = platform().resetAhaMoments()

    // Certificate Pinning operations

    /**
     * Check if certificate pinning is enabled
     */
    fun isCertificatePinningEnabled(): Boolean = platform().isCertificatePinningEnabled()

    /**
     * Set the pinned certificate for relay TLS connections
     * @param certPem Certificate in PEM format
     */
    fun setPinnedCertificate(certPem: String) = platform().setPinnedCertificate(certPem)

    // Duress PIN operations

    /**
     * Authenticate with app password. Returns the auth mode (Normal or Duress).
     * Core sets internal auth_mode which controls contact visibility (decoy vs real).
     */
    fun authenticate(password: String): uniffi.vauchi_platform.MobileAuthMode = platform().authenticate(password)

    /**
     * Set up app password (prerequisite for duress PIN).
     */
    fun setupAppPassword(password: String) {
        platform().setupAppPassword(password)
    }

    /**
     * Check if app password is configured.
     */
    fun isPasswordEnabled(): Boolean = platform().isPasswordEnabled()

    /**
     * Check if duress PIN is enabled
     */
    fun isDuressEnabled(): Boolean = platform().isDuressEnabled()

    /**
     * Set up duress PIN (requires app password to be set first)
     */
    fun setupDuressPassword(duressPassword: String) {
        platform().setupDuressPassword(duressPassword)
    }

    /**
     * Disable duress PIN
     */
    fun disableDuress() {
        platform().disableDuress()
    }

    // Decoy contact management (duress mode profile)

    fun addDecoyContact(
        name: String,
        cardJson: String,
    ): String = platform().addDecoyContact(name, cardJson)

    fun listDecoyContacts(): List<uniffi.vauchi_platform.MobileDecoyContact> = platform().listDecoyContacts()

    fun deleteDecoyContact(id: String) {
        platform().deleteDecoyContact(id)
    }

    fun hideContact(contactId: String) {
        platform().hideContact(contactId)
    }

    fun unhideContact(contactId: String) {
        platform().unhideContact(contactId)
    }

    fun listHiddenContacts(): List<uniffi.vauchi_platform.MobileContact> = platform().listHiddenContacts()

    fun configureDuressAlerts(
        contactIds: List<String>,
        message: String,
    ) {
        platform().configureDuressAlerts(contactIds, message)
    }

    fun getDuressSettings(): uniffi.vauchi_platform.MobileDuressSettings? = platform().getDuressSettings()

    // Contact notes

    fun setContactNote(
        contactId: String,
        note: String,
    ) {
        platform().setContactNote(contactId, note)
    }

    fun getContactNote(contactId: String): String? = platform().getContactNote(contactId)

    fun deleteContactNote(contactId: String) {
        platform().deleteContactNote(contactId)
    }

    fun setContactFieldNote(
        contactId: String,
        fieldId: String,
        note: String,
    ) {
        platform().setContactFieldNote(contactId, fieldId, note)
    }

    fun getContactFieldNotes(contactId: String): List<uniffi.vauchi_platform.MobileFieldNote> = platform().getContactFieldNotes(contactId)

    fun deleteContactFieldNote(
        contactId: String,
        fieldId: String,
    ) {
        platform().deleteContactFieldNote(contactId, fieldId)
    }

    // Proposal trust

    fun setProposalTrusted(
        contactId: String,
        trusted: Boolean,
    ) {
        platform().setProposalTrusted(contactId, trusted)
    }

    // Panic Shred operations

    /**
     * Execute emergency panic shred — destroys all data immediately
     */
    fun panicShred() = platform().panicShred()

    fun softShred(): uniffi.vauchi_platform.MobileShredToken = platform().softShred()

    fun cancelShred(token: uniffi.vauchi_platform.MobileShredToken) {
        platform().cancelShred(token)
    }

    fun hardShred(token: uniffi.vauchi_platform.MobileShredToken): uniffi.vauchi_platform.MobileShredReport = platform().hardShred(token)

    fun shredStatus(): uniffi.vauchi_platform.MobileShredStatus = platform().shredStatus()

    // Emergency Broadcast operations

    /**
     * Configure emergency broadcast
     */
    fun configureEmergencyBroadcast(
        contactIds: List<String>,
        message: String,
        includeLocation: Boolean,
    ) {
        platform().configureEmergencyBroadcast(contactIds, message, includeLocation)
    }

    /**
     * Get emergency broadcast config
     */
    fun getEmergencyConfig(): uniffi.vauchi_platform.MobileEmergencyConfig? = platform().getEmergencyConfig()

    /**
     * Send emergency broadcast
     */
    fun sendEmergencyBroadcast(): uniffi.vauchi_platform.MobileBroadcastResult = platform().sendEmergencyBroadcast()

    /**
     * Disable emergency broadcast
     */
    fun disableEmergencyBroadcast() {
        platform().disableEmergencyBroadcast()
    }

    // Verification operations
    fun verifyContact(id: String) = platform().verifyContact(id)

    fun getPublicKey(): String = platform().getPublicId()

    fun getOwnFingerprint(): String = platform().getOwnFingerprint()

    // Recovery trust operations
    fun trustContactForRecovery(id: String) = platform().trustContactForRecovery(id)

    fun untrustContactForRecovery(id: String) = platform().untrustContactForRecovery(id)

    // Recovery operations
    // `createRecoveryClaim` + `createRecoveryVoucher` retained despite no
    // production consumer: VauchiRepositoryFfiTest asserts the UniFFI
    // passthroughs at the repository layer (android-test suite).
    fun createRecoveryClaim(oldPkHex: String) = platform().createRecoveryClaim(oldPkHex)

    fun createRecoveryVoucher(claimB64: String) = platform().createRecoveryVoucher(claimB64)

    fun addRecoveryVoucher(voucherB64: String) = platform().addRecoveryVoucher(voucherB64)

    fun getRecoveryStatus() = platform().getRecoveryStatus()

    fun getRecoveryProof(): String? = platform().getRecoveryProof()

    fun verifyRecoveryProof(proofB64: String) = platform().verifyRecoveryProof(proofB64)

    // Delivery status operations
    fun getAllDeliveryRecords() = platform().getAllDeliveryRecords()

    /** G3 (ADR-021/043): pre-filtered failed-record list — frontends should
     *  call this instead of `.filter { it.status == FAILED }` themselves. */
    fun getFailedDeliveryRecords() = platform().getFailedDeliveryRecords()

    fun getDeliveryRecordsForContact(contactId: String) = platform().getDeliveryRecordsForContact(contactId)

    fun getDeliverySummary(messageId: String) = platform().getDeliverySummary(messageId)

    fun getDueRetries() = platform().getDueRetries()

    fun countFailedDeliveries(): UInt = platform().countFailedDeliveries()

    fun manualRetry(messageId: String): Boolean = platform().manualRetry(messageId)

    // Demo contact operations
    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     *
     * @return The demo contact if created, null if user has contacts or demo was dismissed
     */
    fun initDemoContactIfNeeded() = platform().initDemoContactIfNeeded()

    /**
     * Get the current demo contact if active.
     *
     * @return The demo contact if active, null otherwise
     */
    fun getDemoContact() = platform().getDemoContact()

    /**
     * Get the demo contact state.
     *
     * @return Current state of the demo contact
     */
    fun getDemoContactState() = platform().getDemoContactState()

    /**
     * Check if a demo update is available.
     *
     * @return True if an update is due (based on 2-hour interval)
     */
    fun isDemoUpdateAvailable(): Boolean = platform().isDemoUpdateAvailable()

    /**
     * Trigger a demo update and get the new content.
     *
     * @return Updated demo contact with new tip, null if demo not active
     */
    fun triggerDemoUpdate() = platform().triggerDemoUpdate()

    /**
     * Dismiss the demo contact manually.
     */
    fun dismissDemoContact() = platform().dismissDemoContact()

    /**
     * Auto-remove demo contact after first real exchange.
     * Call this after a successful contact exchange.
     *
     * @return True if demo was removed, false if it wasn't active
     */
    fun autoRemoveDemoContact(): Boolean = platform().autoRemoveDemoContact()

    /**
     * Restore the demo contact from Settings.
     *
     * @return The restored demo contact
     */
    fun restoreDemoContact() = platform().restoreDemoContact()

    // Device Linking operations
    // Device Linking Protocol operations (relay transport)

    /**
     * Start the device link protocol as initiator (primary device).
     * Returns an initiator state machine that generates QR data and drives the protocol.
     */
    fun startDeviceLink() = platform().startDeviceLink()

    fun startDeviceJoin(
        qrData: String,
        deviceName: String,
    ) = platform().startDeviceJoin(qrData, deviceName)

    /**
     * Listen for an incoming device link request via the relay.
     */
    fun listenForDeviceLinkRequest(timeoutSecs: ULong) = platform().listenForDeviceLinkRequest(timeoutSecs)

    /**
     * Send a device link response back via the relay.
     */
    fun sendDeviceLinkResponse(
        senderToken: String,
        encryptedResponse: ByteArray,
    ) = platform().sendDeviceLinkResponse(senderToken, encryptedResponse)

    /**
     * Create a new device-link orchestration session (Phase 1: initiator only).
     * Core's cycle thread owns QR generation, request listening, state transitions,
     * and persistence. Frontend wires a `DeviceLinkSessionListener` for events.
     */
    fun createDeviceLinkSessionInitiator() = platform().createDeviceLinkSessionInitiator()

    /**
     * Send a device link request via the relay and wait for a response.
     */
    fun sendDeviceLinkRequest(
        targetIdentity: String,
        senderToken: String,
        encryptedRequest: ByteArray,
        timeoutSecs: ULong,
    ): ByteArray =
        platform().sendDeviceLinkRequest(
            targetIdentity,
            senderToken,
            encryptedRequest,
            timeoutSecs,
        )

    // GDPR operations
    // Based on: features/privacy_compliance.feature

    /**
     * Export all user data as GDPR-compliant JSON.
     *
     * @return GDPR export with JSON data, timestamp, and version
     */
    fun exportGdprData() = platform().exportGdprData()

    /**
     * Schedule account deletion with 7-day grace period.
     *
     * @return Deletion info with state and timing
     */
    fun scheduleIdentityDeletion() = platform().scheduleIdentityDeletion()

    /**
     * Cancel a scheduled account deletion.
     */
    fun cancelIdentityDeletion() = platform().cancelIdentityDeletion()

    /**
     * Get current deletion state.
     *
     * @return Current deletion info
     */
    fun getDeletionState() = platform().getDeletionState()

    /**
     * Grant consent for a specific type.
     *
     * @param consentType The type of consent to grant
     */
    fun grantConsent(consentType: uniffi.vauchi_platform.MobileConsentType) = platform().grantConsent(consentType)

    /**
     * Revoke consent for a specific type.
     *
     * @param consentType The type of consent to revoke
     */
    fun revokeConsent(consentType: uniffi.vauchi_platform.MobileConsentType) = platform().revokeConsent(consentType)

    /**
     * Check if consent is granted for a specific type.
     *
     * @param consentType The type to check
     * @return True if consent is currently granted
     */
    fun checkConsent(consentType: uniffi.vauchi_platform.MobileConsentType): Boolean = platform().checkConsent(consentType)

    /**
     * Get all consent records.
     *
     * @return List of all consent records
     */
    fun getConsentRecords() = platform().getConsentRecords()

    fun getConsentStatus(consentType: uniffi.vauchi_platform.MobileConsentType) = platform().getConsentStatus(consentType)

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
