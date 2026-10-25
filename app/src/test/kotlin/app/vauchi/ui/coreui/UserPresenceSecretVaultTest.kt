// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.vauchi_platform.MobileEvent
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Core's user-presence commands in, the event core reads out
 * (vauchi/private#712). Only the keystore and the OS prompt are faked: the
 * keystore with a software AES-GCM key, the prompt by handing back whatever
 * cipher it was given — the shape a `BiometricPrompt.CryptoObject` returns.
 */
class UserPresenceSecretVaultTest {
    private val reason = "Confirm it is you"
    private val unavailable = MobileEvent.HardwareUnavailable(transport = "user_presence")
    private val cancelled = MobileEvent.UserPresenceCheckCancelled
    private val missing = MobileEvent.UserPresenceSecretMissing
    private val create = CommandDTO.CreateUserPresenceSecret(reason, UserPresenceBinding.BIOMETRY)

    private val keystore = SoftwareKeystore()
    private val prompt = FakePrompt()
    private val store = MemoryStore()
    private val vault = UserPresenceSecretVault(keystore, prompt, store)

    @Test
    fun create_answers_32_fresh_bytes_after_the_check_with_cores_reason() =
        runTest {
            val secret = createdSecret(vault.answer(create))

            assertEquals(32, secret.size)
            assertFalse("the vault wiped the secret core was handed", secret.all { it == 0.toByte() })
            assertEquals(listOf(reason), prompt.reasons)
        }

    @Test
    fun reveal_answers_the_secret_create_sealed() =
        runTest {
            val created = createdSecret(vault.answer(create))

            val revealed = revealedSecret(vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))

            assertArrayEquals(created, revealed)
        }

    @Test
    fun only_the_wrapped_secret_is_stored() =
        runTest {
            val created = createdSecret(vault.answer(create))

            val sealed = store.load()!!
            assertEquals(created.size + GCM_TAG_BYTES, sealed.ciphertext.size)
            assertEquals(false, sealed.ciphertext.copyOf(created.size).contentEquals(created))
        }

    /**
     * A biometric key stops opening when a fingerprint or face is enrolled,
     * which needs only the PIN; the prompt asks for that check alone
     * (vauchi/private#778).
     */
    @Test
    fun create_seals_under_the_check_core_names_and_prompts_for_it() =
        runTest {
            for (binding in UserPresenceBinding.entries) {
                createdSecret(vault.answer(CommandDTO.CreateUserPresenceSecret(reason, binding)))
            }

            assertEquals(UserPresenceBinding.entries, keystore.sealedBindings)
            assertEquals(UserPresenceBinding.entries, prompt.bindings)
        }

    @Test
    fun reveal_prompts_for_the_check_the_secret_was_sealed_under() =
        runTest {
            vault.answer(CommandDTO.CreateUserPresenceSecret(reason, UserPresenceBinding.DEVICE_CREDENTIAL))

            revealedSecret(vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))

            assertEquals(
                listOf(UserPresenceBinding.DEVICE_CREDENTIAL, UserPresenceBinding.DEVICE_CREDENTIAL),
                prompt.bindings,
            )
        }

    /** A secret sealed before 0.88.0 records no binding; its key takes either check. */
    @Test
    fun a_secret_sealed_without_a_binding_reveals_with_either_check() =
        runTest {
            val created = createdSecret(vault.answer(create))
            val sealed = store.load()!!
            store.save(WrappedSecret(sealed.iv, sealed.ciphertext))

            assertArrayEquals(created, revealedSecret(vault.answer(CommandDTO.RevealUserPresenceSecret(reason))))
            assertEquals(listOf(UserPresenceBinding.BIOMETRY, null), prompt.bindings)
        }

    @Test
    fun create_without_a_binding_answers_unavailable_and_seals_nothing() =
        runTest {
            assertEquals(unavailable, vault.answer(CommandDTO.CreateUserPresenceSecret(reason, null)))
            assertEquals(emptyList<String>(), prompt.reasons)
            assertNull(store.load())
            assertEquals(emptyList<UserPresenceBinding>(), keystore.sealedBindings)
        }

    @Test
    fun a_second_create_replaces_the_sealed_secret() =
        runTest {
            vault.answer(create)
            val second = createdSecret(vault.answer(create))

            val revealed = revealedSecret(vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))

            assertArrayEquals(second, revealed)
        }

    @Test
    fun dismissing_the_check_on_reveal_answers_cancelled() =
        runTest {
            vault.answer(create)
            prompt.next = { UserPresenceCheck.Cancelled }

            assertEquals(cancelled, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun dismissing_the_check_on_create_answers_cancelled_and_seals_nothing() =
        runTest {
            prompt.next = { UserPresenceCheck.Cancelled }

            assertEquals(cancelled, vault.answer(create))
            assertNull(store.load())
        }

    @Test
    fun a_failed_check_answers_unavailable() =
        runTest {
            vault.answer(create)
            prompt.next = { UserPresenceCheck.Failed }

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun a_success_that_did_not_unlock_the_key_yields_no_secret() =
        runTest {
            prompt.next = { UserPresenceCheck.Unlocked(Cipher.getInstance(TRANSFORMATION)) }

            assertEquals(unavailable, vault.answer(create))
            assertNull(store.load())
        }

    /**
     * The secret is gone, or its key stopped opening after an enrolment
     * change: core refuses there instead of falling back as it does for a
     * device that cannot run the check (vauchi/private#778).
     */
    @Test
    fun reveal_without_a_sealed_secret_answers_missing_without_prompting() =
        runTest {
            assertEquals(missing, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
            assertEquals(emptyList<String>(), prompt.reasons)
        }

    @Test
    fun reveal_with_an_invalidated_key_answers_missing_and_drops_the_stale_secret() =
        runTest {
            vault.answer(create)
            keystore.invalidated = true

            assertEquals(missing, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
            assertEquals(listOf(reason), prompt.reasons)
            assertNull(store.load())
        }

    @Test
    fun a_keystore_that_cannot_open_the_key_answers_unavailable_and_keeps_the_secret() =
        runTest {
            vault.answer(create)
            keystore.decryptBroken = true

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
            assertEquals(listOf(reason), prompt.reasons)
            assertEquals(false, store.load() == null)
        }

    @Test
    fun a_tampered_sealed_secret_answers_unavailable() =
        runTest {
            vault.answer(create)
            val sealed = store.load()!!
            sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()
            store.save(sealed)

            assertEquals(unavailable, vault.answer(CommandDTO.RevealUserPresenceSecret(reason)))
        }

    @Test
    fun a_keystore_that_cannot_make_the_key_answers_unavailable_without_prompting() =
        runTest {
            keystore.broken = true

            assertEquals(unavailable, vault.answer(create))
            assertEquals(emptyList<String>(), prompt.reasons)
        }

    private fun createdSecret(event: MobileEvent): ByteArray =
        (event as? MobileEvent.UserPresenceSecretCreated)?.secret
            ?: throw AssertionError("expected UserPresenceSecretCreated, got ${event::class.simpleName}")

    private fun revealedSecret(event: MobileEvent): ByteArray =
        (event as? MobileEvent.UserPresenceSecretRevealed)?.secret
            ?: throw AssertionError("expected UserPresenceSecretRevealed, got ${event::class.simpleName}")

    private class SoftwareKeystore : UserPresenceKeystore {
        var key: SecretKey? = null
        var invalidated = false
        var broken = false
        var decryptBroken = false
        val sealedBindings = mutableListOf<UserPresenceBinding>()

        override fun newEncryptCipher(binding: UserPresenceBinding): Cipher {
            check(!broken) { "no keystore" }
            sealedBindings += binding
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = fresh
            invalidated = false
            return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fresh) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher? {
            check(!decryptBroken) { "keystore unavailable" }
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
        val bindings = mutableListOf<UserPresenceBinding?>()
        var next: (Cipher) -> UserPresenceCheck = { UserPresenceCheck.Unlocked(it) }

        override suspend fun authenticate(
            reason: String,
            cipher: Cipher,
            binding: UserPresenceBinding?,
        ): UserPresenceCheck {
            reasons += reason
            bindings += binding
            return next(cipher)
        }
    }

    private class MemoryStore : WrappedSecretStore {
        private var sealed: WrappedSecret? = null

        override fun save(sealed: WrappedSecret) {
            this.sealed = WrappedSecret(sealed.iv.copyOf(), sealed.ciphertext.copyOf(), sealed.binding)
        }

        override fun load(): WrappedSecret? =
            sealed?.let { WrappedSecret(it.iv.copyOf(), it.ciphertext.copyOf(), it.binding) }

        override fun clear() {
            sealed = null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BYTES = 16
    }
}
