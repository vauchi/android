// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.util.Base64
import java.io.File

/** The storage-key provider from before #580, failing on decrypt when told to. */
class OldStorageKeyProvider(
    var failure: Exception? = null,
) : StorageKeyProvider {
    private val real = KeyStoreHelper()

    override fun generateEncryptedStorageKey(): ByteArray = real.generateEncryptedStorageKey()

    override fun encryptStorageKey(storageKey: ByteArray): ByteArray = real.encryptStorageKey(storageKey)

    override fun decryptStorageKey(encryptedData: ByteArray): ByteArray {
        failure?.let { throw it }
        return real.decryptStorageKey(encryptedData)
    }

    override fun hasMasterKey(): Boolean = real.hasMasterKey()

    override fun deleteMasterKey() = real.deleteMasterKey()
}

/** The installs Core's `legacy_install_fixture` example writes, under [LEGACY_STORAGE_KEY]. */
enum class LegacyInstall(
    val directory: String,
) {
    /** Opened once, no identity yet. */
    EMPTY("empty"),

    /** Identity "Before" and one imported contact, Carol. */
    BEFORE("before"),
}

private val LEGACY_STORAGE_KEY = ByteArray(32) { 0x58 }

/**
 * An install from before vauchi/private#580: the app's own storage key in
 * preferences and a database under it. The database is checked in, because
 * the binding no longer has the constructor that wrote one. Needs
 * [app.vauchi.screenshots.FakeAndroidKeyStore].
 */
fun installFromBefore(
    context: Context,
    provider: OldStorageKeyProvider,
    install: LegacyInstall = LegacyInstall.EMPTY,
) {
    val encrypted = provider.encryptStorageKey(LEGACY_STORAGE_KEY)
    context
        .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PreferencesLegacyStorageKey.PREF_ENCRYPTED_STORAGE_KEY, Base64.encodeToString(encrypted, Base64.DEFAULT))
        .commit()
    val database = checkNotNull(LegacyInstall::class.java.getResourceAsStream("/legacy-install/${install.directory}/vauchi.db"))
    database.use { source -> File(context.filesDir, "vauchi.db").outputStream().use(source::copyTo) }
}
