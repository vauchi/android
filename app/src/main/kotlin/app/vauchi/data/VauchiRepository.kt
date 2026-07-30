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
import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAhaMoment
import uniffi.vauchi_platform.MobileAhaMomentType
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.PlatformAppEngine
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
 * Repository class wrapping the single PlatformAppEngine UniFFI handle.
 * Uses Android KeyStore for secure storage key management.
 */
class VauchiRepository internal constructor(
    private val context: Context,
    private val keyStoreHelper: StorageKeyProvider = KeyStoreHelper(),
) {
    private lateinit var _appEngine: PlatformAppEngine
    private var initialized = false
    private val prefs: SharedPreferences
    private val preferences: VauchiPreferences

    companion object {
        private const val KEY_ENCRYPTED_STORAGE_KEY = "encrypted_storage_key"

        @Volatile
        private var instance: VauchiRepository? = null

        /**
         * Process-wide singleton. There must be exactly one [PlatformAppEngine]
         * per process: multiple engines share the same on-disk state and can
         * race on global core resources such as the locale catalog, causing
         * screens that were already rendered correctly to revert to
         * "Missing: ..." placeholders.
         */
        fun getInstance(context: Context): VauchiRepository =
            instance ?: synchronized(this) {
                instance ?: VauchiRepository(context.applicationContext).also { instance = it }
            }

        // S4 of `2026-05-16-settings-storage-by-sensitivity`: render-
        // context state (theme + locale) lives in the same OS-native
        // SharedPreferences files [ThemeManager] / [LocalizationManager]
        // already use. The migration below copies any existing vault row
        // into these files exactly once per upgrade.
    }

    init {
        prefs = context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        preferences = VauchiPreferences(prefs)

        // Pre-check: device must have a secure lock screen for KeyStore operations.
        // This is a fast check (no KeyStore access). The actual KeyStore entry is
        // created lazily in ensureInitialized() on first use — NOT here.
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isDeviceSecure) {
            throw DeviceNotSecureException(
                "A secure lock screen (PIN, pattern, or biometric) is required to protect your data. " +
                    "Please set one up in your device Settings.",
            )
        }
    }

    /**
     * Lazily initialize the engine on first use.
     *
     * This defers KeyStore entry creation from app startup to the first actual
     * operation (e.g., createIdentity, hasIdentity). This prevents the bug where
     * force-stopping during onboarding left a KeyStore entry that trapped users
     * in an "Authentication Required" loop.
     */
    @Synchronized
    private fun ensureInitialized() {
        if (!initialized) {
            // Ensure locale files are extracted and the core i18n store is
            // loaded before the engine is created, so the first rendered
            // screen uses the full locale catalog instead of the embedded
            // fallback. This is defensive: the manager is also created from
            // MainViewModel, but engine creation may race past extraction on
            // fresh installs or after an app update.
            LocalizationManager.getInstance(context)

            val dataDir = context.filesDir.absolutePath
            val relayUrl = preferences.getRelayUrl()
            val storageKeyBytes = getOrCreateStorageKey(dataDir)
            _appEngine = PlatformAppEngine(dataDir, relayUrl, storageKeyBytes)
            // Wire the keychain so core-driven shred DomainCommands (SoftShred /
            // CancelShred / HardShred / PanicShred) reach the platform keychain.
            // `widget_panic_shred` is a free function and needs no instance.
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
    }

    /**
     * Shared PlatformAppEngine for core-driven screen rendering.
     * Initialized on first use via ensureInitialized() —
     * single DB connection, shared cache across all screens.
     * Call [platform] first to ensure initialization.
     */
    val appEngine: PlatformAppEngine
        get() {
            ensureInitialized()
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

    fun sync(): MobileSyncResult {
        ensureInitialized()
        val result = _appEngine.sync()
        if (app.vauchi.BuildConfig.DEBUG) {
            // Device-test diagnostics: numeric sync counts only (no PII /
            // contact names) — localizes the send vs receive leg per device.
            Log.d(
                "VauchiSync",
                "sync: contactsAdded=${result.contactsAdded} " +
                    "cardsUpdated=${result.cardsUpdated} " +
                    "updatesSent=${result.updatesSent} " +
                    "blobsFetched=${result.blobsFetched} " +
                    "rejected=${result.rejected} unresolved=${result.unresolved} " +
                    "hasChanges=${result.hasChanges}",
            )
        }
        return result
    }

    /**
     * Try to trigger an aha [momentType] and return the localized moment if
     * it should be shown now, or `null` if already seen. Errors are swallowed
     * so a milestone failure never breaks the calling flow.
     */
    fun tryTriggerAhaMoment(momentType: MobileAhaMomentType): MobileAhaMoment? =
        runCatching {
            val result = appEngine.dispatchDomainCommand(DomainCommand.TryTriggerAhaMoment(momentType))
            (result as? DomainCommandResult.AhaMomentOpt)?.moment
        }.getOrNull()

    fun hasIdentity(): Boolean {
        ensureInitialized()
        return appEngine.hasIdentity()
    }

    fun createIdentity(displayName: String) {
        ensureInitialized()
        appEngine.createIdentity(displayName)
    }

    fun getDisplayName(): String {
        ensureInitialized()
        return appEngine.getDisplayName()
    }

    fun getPublicId(): String {
        ensureInitialized()
        return appEngine.getPublicId()
    }

    fun getOwnCard(): MobileContactCard {
        ensureInitialized()
        return appEngine.getOwnCard()
    }

    fun contactCount(): UInt = appEngine.contactCount()

    fun importBackup(
        backupData: String,
        password: String,
    ) {
        ensureInitialized()
        appEngine.importBackup(backupData, password)
    }

    fun importFullBackup(
        backupData: String,
        password: String,
    ) {
        ensureInitialized()
        appEngine.importBackup(backupData, password)
    }

    // Based on: features/content_updates.feature

    /**
     * Run the whole content-update cycle (check → apply → screen
     * invalidation) in core and return its presentation-only outcome.
     * Unlike the UI read methods this does *not* swallow errors: the
     * background worker maps a thrown dispatch failure to a WorkManager
     * retry, so the failure must propagate.
     */
    fun runContentUpdateCycle(): uniffi.vauchi_platform.MobileContentCycleOutcome = appEngine.runContentUpdateCycle()

    /**
     * Authenticate with app password. Returns the auth mode (Normal or Duress).
     * Core sets internal auth_mode which controls contact visibility (decoy vs real).
     */
    fun authenticate(password: String): uniffi.vauchi_platform.MobileAuthMode = appEngine.authenticate(password)

    // Based on: features/demo_contact.feature

    /**
     * Initialize demo contact if user has no real contacts.
     * Call this after onboarding completes.
     *
     * @return The demo contact if created, null if user has contacts or demo was dismissed
     */
    fun initDemoContactIfNeeded(): uniffi.vauchi_platform.MobileDemoContact? {
        ensureInitialized()
        return appEngine.initDemoContactIfNeeded()
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
