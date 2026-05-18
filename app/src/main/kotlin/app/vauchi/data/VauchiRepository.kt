// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

// DONE: Content updates - isContentUpdatesSupported(), checkContentUpdates(),
// applyContentUpdates(), reloadSocialNetworks() methods implemented.
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
import app.vauchi.util.LocalizationManager
import app.vauchi.util.ThemeManager
import uniffi.vauchi_platform.MobileAppPreferences
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileExchangeResult
import uniffi.vauchi_platform.MobileExchangeSession
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileMultiStageSession
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

        // Phase 2a/A3a legacy SharedPreferences keys forwarded into the
        // core `app_preferences` row by [migrateLegacyAppPreferences].
        // See [ThemeManager] / [LocalizationManager] for the original
        // names — kept here so the migration runs even when the
        // managers are not yet instantiated.
        private const val LEGACY_THEME_PREFS = "vauchi_theme_settings"
        private const val LEGACY_LOCALE_PREFS = "vauchi_locale_settings"
        private const val LEGACY_THEME_KEY = "selected_theme_id"
        private const val LEGACY_LOCALE_KEY = "selected_locale_code"
        private const val LEGACY_FOLLOW_SYSTEM_KEY = "follow_system"

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

            // Phase 2a/A3a — wire theme + language managers to the
            // singleton AppPreferences row so the inline Settings
            // dropdown becomes the single source of truth. Migrates any
            // legacy SharedPreferences picks once, on first run after
            // upgrade. See problem record
            // 2026-05-01-android-humble-ui-deep-retirement.
            migrateLegacyAppPreferences()
            ThemeManager.getInstance(context).attachAppEngine(_appEngine)
            LocalizationManager.getInstance(context).attachAppEngine(_appEngine)
        }
        return _vauchi
    }

    /**
     * Forwards legacy theme + language picks stored in
     * [ThemeManager]'s and [LocalizationManager]'s SharedPreferences to
     * the core `app_preferences` row, then clears the legacy keys.
     *
     * Idempotent: only runs when the core row is at default
     * (`follow_system_*` both true, both ids null) — i.e. the user has
     * not yet picked through the new Settings dropdown. After migration
     * the SharedPreferences keys are cleared so subsequent runs short-
     * circuit on the default-row check.
     *
     * Safe to call before identity creation (storage-only).
     */
    private fun migrateLegacyAppPreferences() {
        val current =
            try {
                _appEngine.getAppPreferences()
            } catch (e: Exception) {
                Log.e("VauchiRepository", "getAppPreferences() failed during migration", e)
                return
            }
        val isDefault =
            current.themeId == null &&
                current.languageCode == null &&
                current.followSystemTheme &&
                current.followSystemLanguage
        if (!isDefault) return

        val legacyTheme =
            context
                .getSharedPreferences(LEGACY_THEME_PREFS, Context.MODE_PRIVATE)
        val legacyLocale =
            context
                .getSharedPreferences(LEGACY_LOCALE_PREFS, Context.MODE_PRIVATE)
        val themeId = legacyTheme.getString(LEGACY_THEME_KEY, null)
        val followSystemTheme = legacyTheme.getBoolean(LEGACY_FOLLOW_SYSTEM_KEY, true)
        val localeCode = legacyLocale.getString(LEGACY_LOCALE_KEY, null)
        val followSystemLocale = legacyLocale.getBoolean(LEGACY_FOLLOW_SYSTEM_KEY, true)

        val hasExplicitTheme = themeId != null || !followSystemTheme
        val hasExplicitLocale = localeCode != null || !followSystemLocale
        if (!hasExplicitTheme && !hasExplicitLocale) return

        val migrated =
            MobileAppPreferences(
                themeId = themeId,
                languageCode = localeCode,
                followSystemTheme = followSystemTheme,
                followSystemLanguage = followSystemLocale,
            )
        try {
            _appEngine.setAppPreferences(migrated)
        } catch (e: Exception) {
            Log.e("VauchiRepository", "setAppPreferences() failed during migration", e)
            return
        }

        legacyTheme
            .edit()
            .remove(LEGACY_THEME_KEY)
            .remove(LEGACY_FOLLOW_SYSTEM_KEY)
            .apply()
        legacyLocale
            .edit()
            .remove(LEGACY_LOCALE_KEY)
            .remove(LEGACY_FOLLOW_SYSTEM_KEY)
            .apply()
    }

    /** Loads the singleton app_preferences row (theme + language). */
    fun getAppPreferences(): MobileAppPreferences {
        platform() // ensure lazy init
        return _appEngine.getAppPreferences()
    }

    /** Saves the singleton app_preferences row (theme + language). */
    fun setAppPreferences(prefs: MobileAppPreferences) {
        platform() // ensure lazy init
        _appEngine.setAppPreferences(prefs)
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

    fun isDeliveryReceiptsEnabled(): Boolean {
        platform() // ensure lazy init
        return _appEngine.isDeliveryReceiptsEnabled()
    }

    fun setDeliveryReceiptsEnabled(enabled: Boolean) {
        platform() // ensure lazy init
        _appEngine.setDeliveryReceiptsEnabled(enabled)
    }

    fun isSuppressPresenceEnabled(): Boolean {
        platform() // ensure lazy init
        return _appEngine.isSuppressPresenceEnabled()
    }

    fun setSuppressPresenceEnabled(enabled: Boolean) {
        platform() // ensure lazy init
        _appEngine.setSuppressPresenceEnabled(enabled)
    }

    fun getSyncStatus(): uniffi.vauchi_platform.MobileSyncStatus = platform().getSyncStatus()

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
                publicId = appEngine.getPublicId(),
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

    fun contactCount(): UInt = appEngine.contactCount()

    fun listContacts() = appEngine.listContacts()

    fun listContactsPaginated(
        offset: UInt,
        limit: UInt,
    ) = appEngine.listContactsPaginated(offset, limit)

    fun searchContacts(query: String) = appEngine.searchContacts(query)

    fun getContact(id: String) = appEngine.getContact(id)

    fun removeContact(id: String) = appEngine.removeContact(id)

    // Contact lifecycle (reversible deletion + archival)

    fun softDeleteImportedContact(id: String) = appEngine.softDeleteImportedContact(id)

    fun undoDeleteImportedContact(id: String) = appEngine.undoDeleteImportedContact(id)

    fun hardDeleteImportedContact(id: String) = appEngine.hardDeleteImportedContact(id)

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

    fun importContactsFromVcf(data: ByteArray) = platform().importContactsFromVcf(data)

    // Visibility operations
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

    // Visibility Labels operations
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

    fun addContactToLabel(
        labelId: String,
        contactId: String,
    ) {
        appEngine.addContactToGroup(labelId, contactId)
    }

    fun removeContactFromLabel(
        labelId: String,
        contactId: String,
    ) {
        appEngine.removeContactFromGroup(labelId, contactId)
    }

    fun getLabelsForContact(contactId: String): List<uniffi.vauchi_platform.MobileVisibilityLabel> =
        appEngine.getGroupsForContact(contactId)

    fun setLabelFieldVisibility(
        labelId: String,
        fieldId: String,
        visible: Boolean,
    ) {
        appEngine.setGroupFieldVisibility(labelId, fieldId, visible)
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

    // Backup operations
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

    // Full backup operations (identity + contacts + own card + labels)
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

    // Social network operations.
    //
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

    // Content Updates operations
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

    // Certificate Pinning operations

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

    // Duress PIN operations

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

    // Contact notes

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

    // Proposal trust

    fun setProposalTrusted(
        contactId: String,
        trusted: Boolean,
    ) {
        appEngine.setProposalTrusted(contactId, trusted)
    }

    // Emergency Broadcast operations

    /**
     * Configure emergency broadcast
     */
    fun configureEmergencyBroadcast(
        contactIds: List<String>,
        message: String,
        includeLocation: Boolean,
    ) {
        appEngine.configureEmergencyBroadcast(contactIds, message, includeLocation)
    }

    /**
     * Get emergency broadcast config
     */
    fun getEmergencyConfig(): uniffi.vauchi_platform.MobileEmergencyConfig? = appEngine.getEmergencyConfig()

    /**
     * Send emergency broadcast
     */
    fun sendEmergencyBroadcast(): uniffi.vauchi_platform.MobileBroadcastResult = appEngine.sendEmergencyBroadcast()

    /**
     * Disable emergency broadcast
     */
    fun disableEmergencyBroadcast() {
        appEngine.disableEmergencyBroadcast()
    }

    // Verification operations
    fun verifyContact(id: String) = appEngine.verifyContact(id)

    fun getPublicKey(): String {
        platform() // ensure initialized
        return appEngine.getPublicId()
    }

    fun getOwnFingerprint(): String = appEngine.getOwnFingerprint()

    // Recovery trust operations (direct B2 typed methods on PlatformAppEngine).
    fun trustContactForRecovery(id: String) = appEngine.trustContactForRecovery(id)

    fun untrustContactForRecovery(id: String) = appEngine.untrustContactForRecovery(id)

    // Recovery operations.
    //
    // C7: Recovery uses direct typed methods on `PlatformAppEngine`
    // (R3 hybrid B2 carve-out — these are not in the `DomainCommand`
    // enum). Mirrors iOS commit `c2db048` C1+C5+C7 mega-MR.
    //
    // `createRecoveryClaim` + `createRecoveryVoucher` retained despite
    // no production consumer: VauchiRepositoryFfiTest asserts the UniFFI
    // passthroughs at the repository layer (android-test suite).
    fun createRecoveryClaim(oldPkHex: String) = appEngine.createRecoveryClaim(oldPkHex)

    fun createRecoveryVoucher(claimB64: String) = appEngine.createRecoveryVoucher(claimB64)

    fun addRecoveryVoucher(voucherB64: String) = appEngine.addRecoveryVoucher(voucherB64)

    fun getRecoveryStatus() = appEngine.getRecoveryStatus()

    fun getRecoveryProof(): String? = appEngine.getRecoveryProof()

    fun verifyRecoveryProof(proofB64: String) = appEngine.verifyRecoveryProof(proofB64)

    // Delivery status operations
    fun getAllDeliveryRecords() = appEngine.getAllDeliveryRecords()

    /** G3 (ADR-021/043): pre-filtered failed-record list — frontends should
     *  call this instead of `.filter { it.status == FAILED }` themselves. */
    fun getFailedDeliveryRecords() = appEngine.getFailedDeliveryRecords()

    fun getDeliveryRecordsForContact(contactId: String) = appEngine.getDeliveryRecordsForContact(contactId)

    fun getDeliverySummary(messageId: String) = appEngine.getDeliverySummary(messageId)

    fun getDueRetries() = appEngine.getDueRetries()

    fun countFailedDeliveries(): UInt = appEngine.countFailedDeliveries()

    fun manualRetry(messageId: String): Boolean = appEngine.manualRetry(messageId)

    // Demo contact operations
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
    fun createDeviceLinkSessionInitiator() = appEngine.createDeviceLinkSessionInitiator()

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
     * Check if consent is granted for a specific type.
     *
     * @param consentType The type to check
     * @return True if consent is currently granted
     */
    fun checkConsent(consentType: uniffi.vauchi_platform.MobileConsentType): Boolean = appEngine.checkConsent(consentType)

    /**
     * Get all consent records.
     *
     * @return List of all consent records
     */
    fun getConsentRecords() = appEngine.getConsentRecords()

    fun getConsentStatus(consentType: uniffi.vauchi_platform.MobileConsentType) = appEngine.getConsentStatus(consentType)

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
