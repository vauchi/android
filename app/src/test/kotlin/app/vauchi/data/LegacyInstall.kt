// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import android.content.Context
import android.util.Base64
import uniffi.vauchi_platform.PlatformAppEngine

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

/**
 * An install from before vauchi/private#580: the app's own storage key in
 * preferences and a database opened under it. Needs the host Core library
 * and [app.vauchi.screenshots.FakeAndroidKeyStore].
 */
fun installFromBefore(
    context: Context,
    provider: OldStorageKeyProvider,
    setUp: (PlatformAppEngine) -> Unit = {},
) {
    val encrypted = provider.generateEncryptedStorageKey()
    context
        .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PreferencesLegacyStorageKey.PREF_ENCRYPTED_STORAGE_KEY, Base64.encodeToString(encrypted, Base64.DEFAULT))
        .commit()
    val key = provider.decryptStorageKey(encrypted)
    PlatformAppEngine(context.filesDir.absolutePath, "https://relay.test", key).use(setUp)
}
