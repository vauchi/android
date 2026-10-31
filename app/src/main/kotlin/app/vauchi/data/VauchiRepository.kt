// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.vauchi.util.LocalizationManager
import app.vauchi.util.NotificationPresentation
import app.vauchi.util.pushDeviceCapabilities
import app.vauchi.util.toPresentation
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.PlatformAppEngine

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
            // Core holds every key that opens the database in the keychain,
            // so a shred destroys them all; the key this app kept before
            // reaches Core through the bridge (vauchi/private#580). A locked
            // or invalidated key starts Core's own unlock or recovery screen.
            val keychain = PlatformKeychainBridge(context, PreferencesLegacyStorageKey(context, keyStoreHelper))
            _appEngine = PlatformAppEngine.openWithKeychain(dataDir, relayUrl, null, keychain)
            initialized = true

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

    fun getRelayUrl(): String = preferences.getRelayUrl()

    fun setRelayUrl(url: String) = preferences.setRelayUrl(url)

    fun getReduceMotion(): Boolean = preferences.getReduceMotion()

    fun setReduceMotion(enabled: Boolean) = preferences.setReduceMotion(enabled)

    fun getHighContrast(): Boolean = preferences.getHighContrast()

    fun setHighContrast(enabled: Boolean) = preferences.setHighContrast(enabled)

    fun getLargeTouchTargets(): Boolean = preferences.getLargeTouchTargets()

    fun setLargeTouchTargets(enabled: Boolean) = preferences.setLargeTouchTargets(enabled)

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

    /**
     * Poll for OS notifications produced by the app engine (E).
     */
    fun pollNotifications(): List<NotificationPresentation> =
        try {
            appEngine.pollNotifications().map { it.toPresentation() }
        } catch (e: Exception) {
            Log.e("VauchiRepository", "pollNotifications failed", e)
            emptyList()
        }
}
