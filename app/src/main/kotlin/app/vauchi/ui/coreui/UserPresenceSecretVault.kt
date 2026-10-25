// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import uniffi.vauchi_platform.MobileEvent
import java.security.SecureRandom
import javax.crypto.Cipher

/** The platform key a user-presence secret is wrapped with. */
interface UserPresenceKeystore {
    /**
     * Replaces any existing key with a fresh one whose every use needs the
     * check [binding] names, and returns a cipher ready to wrap with it.
     */
    fun newEncryptCipher(binding: UserPresenceBinding): Cipher

    /** A cipher to unwrap with the current key; `null` when it is gone or invalidated. */
    fun decryptCipher(iv: ByteArray): Cipher?

    fun delete()
}

/** The OS user-presence check, bound to the cipher it unlocks. */
interface UserPresencePrompt {
    /** [binding] is the check the key was sealed under; `null` for a key sealed before 0.88.0. */
    suspend fun authenticate(
        reason: String,
        cipher: Cipher,
        binding: UserPresenceBinding?,
    ): UserPresenceCheck
}

sealed interface UserPresenceCheck {
    /** The check passed and the OS handed back the cipher it unlocked. */
    data class Unlocked(
        val cipher: Cipher,
    ) : UserPresenceCheck

    data object Cancelled : UserPresenceCheck

    data object Failed : UserPresenceCheck
}

/** A user-presence secret as wrapped by the keystore key: never the secret itself. */
class WrappedSecret(
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val binding: UserPresenceBinding? = null,
)

interface WrappedSecretStore {
    fun save(sealed: WrappedSecret)

    fun load(): WrappedSecret?

    fun clear()
}

/**
 * Answers core's `CreateUserPresenceSecret` / `RevealUserPresenceSecret`
 * (vauchi/private#712) with the event core expects.
 *
 * The secret only ever exists unwrapped inside a cipher the OS unlocked for
 * this one use, so a prompt that reports success without unlocking the key
 * yields no secret. Core keeps the secret's SHA-256 and enrols it once, so a
 * key the OS dropped (lock screen removed, or a biometric enrolment changed)
 * cannot be recreated behind core's back: the answer is
 * `UserPresenceSecretMissing` and core decides the way back (#778).
 */
class UserPresenceSecretVault(
    private val keystore: UserPresenceKeystore,
    private val prompt: UserPresencePrompt,
    private val store: WrappedSecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun answer(command: UserPresenceCommand): MobileEvent =
        when (command) {
            is CommandDTO.CreateUserPresenceSecret -> command.binding?.let { create(command.prompt, it) } ?: UNAVAILABLE
            is CommandDTO.RevealUserPresenceSecret -> reveal(command.prompt)
        }

    private suspend fun create(
        reason: String,
        binding: UserPresenceBinding,
    ): MobileEvent {
        store.clear()
        val cipher = runCatching { keystore.newEncryptCipher(binding) }.getOrNull() ?: return UNAVAILABLE
        val unlocked =
            when (val check = prompt.authenticate(reason, cipher, binding)) {
                is UserPresenceCheck.Unlocked -> check.cipher
                UserPresenceCheck.Cancelled -> return CANCELLED
                UserPresenceCheck.Failed -> return UNAVAILABLE
            }
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        try {
            val ciphertext = runCatching { unlocked.doFinal(secret) }.getOrNull() ?: return UNAVAILABLE
            store.save(WrappedSecret(unlocked.iv, ciphertext, binding))
            return MobileEvent.UserPresenceSecretCreated(secret.copyOf())
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun reveal(reason: String): MobileEvent {
        val wrapped = store.load() ?: return MISSING
        val cipher = runCatching { keystore.decryptCipher(wrapped.iv) }.getOrElse { return UNAVAILABLE }
        if (cipher == null) {
            store.clear()
            runCatching { keystore.delete() }
            return MISSING
        }
        val unlocked =
            when (val check = prompt.authenticate(reason, cipher, wrapped.binding)) {
                is UserPresenceCheck.Unlocked -> check.cipher
                UserPresenceCheck.Cancelled -> return CANCELLED
                UserPresenceCheck.Failed -> return UNAVAILABLE
            }
        val secret = runCatching { unlocked.doFinal(wrapped.ciphertext) }.getOrNull() ?: return UNAVAILABLE
        try {
            if (secret.size != SECRET_BYTES) return UNAVAILABLE
            return MobileEvent.UserPresenceSecretRevealed(secret.copyOf())
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        private const val SECRET_BYTES = 32

        /** Core reads `HardwareUnavailable` for this transport as "no user-presence check here". */
        val UNAVAILABLE = MobileEvent.HardwareUnavailable(transport = "user_presence")

        val CANCELLED = MobileEvent.UserPresenceCheckCancelled

        /** The sealed secret or its key is gone; unlike [UNAVAILABLE], the check itself works here. */
        val MISSING = MobileEvent.UserPresenceSecretMissing
    }
}
