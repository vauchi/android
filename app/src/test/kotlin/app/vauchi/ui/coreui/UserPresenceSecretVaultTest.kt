// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Core's user-presence commands in, the event JSON core reads out
 * (vauchi/private#712). Only the keystore and the OS prompt are faked: the
 * keystore with a software AES-GCM key, the prompt by handing back whatever
 * cipher it was given — the shape a `BiometricPrompt.CryptoObject` returns.
 */
class UserPresenceSecretVaultTest {
    private val reason = "Confirm it is you"
    private val unavailable = """{"HardwareUnavailable":{"transport":"user_presence"}}"""
    private val cancelled = "\"UserPresenceCheckCancelled\""

    private val keystore = SoftwareKeystore()
    private val prompt = FakePrompt()
    private val store = MemoryStore()
    private val vault = UserPresenceSecretVault(keystore, prompt, store)

    @Test
    fun create_answers_32_fresh_bytes_after_the_check_with_cores_reason() =
        runTest {
            val event = vault.answer(CommandDTO.CreateUserPresenceSecret(reason))

            assertEquals(32, secretOf(event, "UserPresenceSecretCreated").size)
            assertEquals(listOf(reason), prompt.reasons)
        }

    @Test
    fun reveal_answers_the_secret_create_sealed() =
        runTest {
            val created = secretOf(vault.answer(CommandDTO.CreateUserPresenceSecret(reason)), "UserPresenceSecretCreated")

            val revealed = secretOf(vault.answer(CommandDTO.RevealUserPresenceSecret(reason)), "UserPresenceSecretRevealed")

            assertArrayEquals(created, revealed)
        }

    @Test
    fun only_the_wrapped_secret_is_stored() =
        runTest {
            val created = secretOf(vault.answer(CommandDTO.CreateUserPresenceSecret(reason)), "UserPresenceSecretCreated")

            val sealed = store.load()!!
            assertEquals(created.size + GCM_TAG_BYTES, sealed.ciphertext.size)
            assertEquals(false, sealed.ciphertext.copyOf(created.size).contentEquals(created))
        }

    @Test
    fun a_second_create_replaces_the_sealed_secret() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason))
            val second = secretOf(vault.answer(CommandDTO.CreateUserPresenceSecret(reason)), "UserPresenceSecretCreated")

            val revealed = secretOf(vault.answer(CommandDTO.RevealUserPresenceSecret(reason)), "UserPresenceSecretRevealed")

            assertArrayEquals(second, revealed)
        }

    @Test
    fun dismissing_the_check_on_reveal_answers_cancelled() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason))
            prompt.next = { UserPresenceCheck.Cancelled }

            assertEquals(cancelled, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun dismissing_the_check_on_create_answers_cancelled_and_seals_nothing() =
        runTest {
            prompt.next = { UserPresenceCheck.Cancelled }

            assertEquals(cancelled, vault.answer(CommandDTO.CreateUserPresenceSecret(reason)))
            assertNull(store.load())
        }

    @Test
    fun a_failed_check_answers_unavailable() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason))
            prompt.next = { UserPresenceCheck.Failed }

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun a_success_that_did_not_unlock_the_key_yields_no_secret() =
        runTest {
            prompt.next = { UserPresenceCheck.Unlocked(Cipher.getInstance(TRANSFORMATION)) }

            assertEquals(unavailable, vault.answer(CommandDTO.CreateUserPresenceSecret(reason)))
            assertNull(store.load())
        }

    @Test
    fun reveal_without_a_sealed_secret_answers_unavailable_without_prompting() =
        runTest {
            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
            assertEquals(emptyList<String>(), prompt.reasons)
        }

    @Test
    fun reveal_with_an_invalidated_key_answers_unavailable_and_drops_the_stale_secret() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason))
            keystore.invalidated = true

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
            assertEquals(listOf(reason), prompt.reasons)
            assertNull(store.load())
        }

    @Test
    fun a_tampered_sealed_secret_answers_unavailable() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason))
            val sealed = store.load()!!
            sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()
            store.save(sealed)

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun a_keystore_that_cannot_make_the_key_answers_unavailable_without_prompting() =
        runTest {
            keystore.broken = true

            assertEquals(unavailable, vault.answer(CommandDTO.CreateUserPresenceSecret(reason)))
            assertEquals(emptyList<String>(), prompt.reasons)
        }

    @Test
    fun the_unavailable_answer_names_core_user_presence_transport() {
        assertEquals(unavailable, UserPresenceSecretVault.UNAVAILABLE_EVENT_JSON)
    }

    private fun secretOf(
        eventJson: String,
        variant: String,
    ): ByteArray {
        val payload = Json.parseToJsonElement(eventJson).jsonObject
        assertEquals(setOf(variant), payload.keys)
        val secret = (payload.getValue(variant) as JsonObject).getValue("secret").jsonArray
        return secret
            .map {
                it.jsonPrimitive.int
                    .also { b -> require(b in 0..255) }
                    .toByte()
            }.toByteArray()
    }

    private class SoftwareKeystore : UserPresenceKeystore {
        var key: SecretKey? = null
        var invalidated = false
        var broken = false

        override fun newEncryptCipher(): Cipher {
            check(!broken) { "no keystore" }
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = fresh
            invalidated = false
            return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fresh) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher? {
            val current = key ?: return null
            if (invalidated) return null
            return Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(GCM_TAG_BYTES * 8, iv))
            }
        }

        override fun delete() {
            key = null
        }
    }

    private class FakePrompt : UserPresencePrompt {
        val reasons = mutableListOf<String>()
        var next: (Cipher) -> UserPresenceCheck = { UserPresenceCheck.Unlocked(it) }

        override suspend fun authenticate(
            reason: String,
            cipher: Cipher,
        ): UserPresenceCheck {
            reasons += reason
            return next(cipher)
        }
    }

    private class MemoryStore : WrappedSecretStore {
        private var sealed: WrappedSecret? = null

        override fun save(sealed: WrappedSecret) {
            this.sealed = WrappedSecret(sealed.iv.copyOf(), sealed.ciphertext.copyOf())
        }

        override fun load(): WrappedSecret? = sealed?.let { WrappedSecret(it.iv.copyOf(), it.ciphertext.copyOf()) }

        override fun clear() {
            sealed = null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BYTES = 16
    }
}
