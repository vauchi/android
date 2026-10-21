// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom
import javax.crypto.Cipher

/** The platform key a user-presence secret is wrapped with. */
interface UserPresenceKeystore {
    /**
     * Replaces any existing key with a fresh one whose every use needs the
     * user-presence check, and returns a cipher ready to wrap with it.
     */
    fun newEncryptCipher(): Cipher

    /** A cipher to unwrap with the current key; `null` when it is gone or invalidated. */
    fun decryptCipher(iv: ByteArray): Cipher?

    fun delete()
}

/** The OS user-presence check, bound to the cipher it unlocks. */
interface UserPresencePrompt {
    suspend fun authenticate(
        reason: String,
        cipher: Cipher,
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
)

interface WrappedSecretStore {
    fun save(sealed: WrappedSecret)

    fun load(): WrappedSecret?

    fun clear()
}

/**
 * Answers core's `CreateUserPresenceSecret` / `RevealUserPresenceSecret`
 * (vauchi/private#712) with the JSON of the event core expects.
 *
 * The secret only ever exists unwrapped inside a cipher the OS unlocked for
 * this one use, so a prompt that reports success without unlocking the key
 * yields no secret. Core keeps the secret's SHA-256 and enrols it once, so a
 * key the OS dropped (lock screen removed) cannot be recreated behind core's
 * back: the answer is `HardwareUnavailable` and core falls back to the app
 * password.
 */
class UserPresenceSecretVault(
    private val keystore: UserPresenceKeystore,
    private val prompt: UserPresencePrompt,
    private val store: WrappedSecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun answer(command: UserPresenceCommand): String =
        when (command) {
            is CommandDTO.CreateUserPresenceSecret -> create(command.prompt)
            is CommandDTO.RevealUserPresenceSecret -> reveal(command.prompt)
        }

    private suspend fun create(reason: String): String {
        store.clear()
        val cipher = runCatching { keystore.newEncryptCipher() }.getOrNull() ?: return UNAVAILABLE_EVENT_JSON
        val unlocked =
            when (val check = prompt.authenticate(reason, cipher)) {
                is UserPresenceCheck.Unlocked -> check.cipher
                UserPresenceCheck.Cancelled -> return CANCELLED_EVENT_JSON
                UserPresenceCheck.Failed -> return UNAVAILABLE_EVENT_JSON
            }
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        try {
            val ciphertext = runCatching { unlocked.doFinal(secret) }.getOrNull() ?: return UNAVAILABLE_EVENT_JSON
            store.save(WrappedSecret(unlocked.iv, ciphertext))
            return secretEventJson("UserPresenceSecretCreated", secret)
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun reveal(reason: String): String {
        val wrapped = store.load() ?: return UNAVAILABLE_EVENT_JSON
        val cipher = runCatching { keystore.decryptCipher(wrapped.iv) }.getOrNull()
        if (cipher == null) {
            store.clear()
            runCatching { keystore.delete() }
            return UNAVAILABLE_EVENT_JSON
        }
        val unlocked =
            when (val check = prompt.authenticate(reason, cipher)) {
                is UserPresenceCheck.Unlocked -> check.cipher
                UserPresenceCheck.Cancelled -> return CANCELLED_EVENT_JSON
                UserPresenceCheck.Failed -> return UNAVAILABLE_EVENT_JSON
            }
        val secret = runCatching { unlocked.doFinal(wrapped.ciphertext) }.getOrNull() ?: return UNAVAILABLE_EVENT_JSON
        try {
            if (secret.size != SECRET_BYTES) return UNAVAILABLE_EVENT_JSON
            return secretEventJson("UserPresenceSecretRevealed", secret)
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        private const val SECRET_BYTES = 32

        /** Core reads `HardwareUnavailable` for this transport as "no user-presence check here". */
        const val UNAVAILABLE_EVENT_JSON = """{"HardwareUnavailable":{"transport":"user_presence"}}"""

        const val CANCELLED_EVENT_JSON = "\"UserPresenceCheckCancelled\""

        private fun secretEventJson(
            variant: String,
            secret: ByteArray,
        ): String =
            buildJsonObject {
                put(
                    variant,
                    buildJsonObject {
                        put("secret", JsonArray(secret.map { JsonPrimitive(it.toInt() and 0xFF) }))
                    },
                )
            }.toString()
    }
}
