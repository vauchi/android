// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import android.security.keystore.KeyGenParameterSpec
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory stand-in for the `AndroidKeyStore` JCA provider, which
 * Robolectric does not ship. `KeyStoreHelper` and `PlatformKeychainBridge`
 * only need alias-addressed AES keys that the default JCE `AES/GCM/NoPadding`
 * cipher accepts, so plain `SecretKeySpec`s stored in a map are enough to
 * let the real Core engine initialise on the host JVM.
 */
object FakeAndroidKeyStore {
    private const val PROVIDER_NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider(PROVIDER_NAME) == null) {
            Security.insertProviderAt(FakeProvider(), 1)
        }
        keys.clear()
    }

    class FakeProvider : Provider(PROVIDER_NAME, 1.0, "Robolectric in-memory AndroidKeyStore") {
        init {
            put("KeyStore.$PROVIDER_NAME", InMemoryKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", AliasedAesKeyGenerator::class.java.name)
        }
    }

    class InMemoryKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? = keys[alias]

        override fun engineGetEntry(
            alias: String,
            protParam: KeyStore.ProtectionParameter?,
        ): KeyStore.Entry? = keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

        override fun engineSetEntry(
            alias: String,
            entry: KeyStore.Entry,
            protParam: KeyStore.ProtectionParameter?,
        ) {
            keys[alias] = (entry as KeyStore.SecretKeyEntry).secretKey
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date() else null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<Certificate>?,
        ) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<Certificate>?,
        ) = throw UnsupportedOperationException("raw key entries are not supported")

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ) = throw UnsupportedOperationException("certificate entries are not supported")

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(
            stream: java.io.OutputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(
            stream: java.io.InputStream?,
            password: CharArray?,
        ) = Unit
    }

    class AliasedAesKeyGenerator : KeyGeneratorSpi() {
        private var alias: String? = null
        private var keySizeBits = 256
        private val random = SecureRandom()

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            val spec = params as KeyGenParameterSpec
            alias = spec.keystoreAlias
            keySizeBits = spec.keySize.takeIf { it > 0 } ?: 256
        }

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ) {
            keySizeBits = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val material = ByteArray(keySizeBits / 8).also(random::nextBytes)
            val key = SecretKeySpec(material, "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }
}
