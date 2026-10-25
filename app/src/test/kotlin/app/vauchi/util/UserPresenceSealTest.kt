// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators
import app.vauchi.screenshots.HostCoreLibrary
import app.vauchi.ui.coreui.UserPresenceBinding
import app.vauchi.ui.coreui.WrappedSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Keystore key and the prompt follow the check Core names
 * (vauchi/private#778): a biometric key that only a strong biometric opens
 * and that an enrolment change invalidates, since enrolling needs only the
 * PIN; a credential key that only the PIN, pattern or password opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [HostCoreLibrary.SDK])
class UserPresenceSealTest {
    @Test
    fun a_biometric_seal_takes_only_a_strong_biometric_and_dies_with_the_enrolment() {
        val seal = UserPresenceSeal.of(UserPresenceBinding.BIOMETRY)

        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, seal.keyAuthenticators)
        assertTrue(seal.invalidatedByBiometricEnrollment)
        assertEquals(Authenticators.BIOMETRIC_STRONG, UserPresenceSeal.promptAuthenticators(UserPresenceBinding.BIOMETRY))
    }

    @Test
    fun a_credential_seal_takes_only_the_device_credential() {
        val seal = UserPresenceSeal.of(UserPresenceBinding.DEVICE_CREDENTIAL)

        assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL, seal.keyAuthenticators)
        assertFalse(seal.invalidatedByBiometricEnrollment)
        assertEquals(
            Authenticators.DEVICE_CREDENTIAL,
            UserPresenceSeal.promptAuthenticators(UserPresenceBinding.DEVICE_CREDENTIAL),
        )
    }

    /** A key sealed before 0.88.0 takes either, so the prompt must offer both to open it. */
    @Test
    fun a_secret_sealed_without_a_binding_is_prompted_with_either_check() {
        assertEquals(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            UserPresenceSeal.promptAuthenticators(null),
        )
    }

    @Test
    fun the_store_keeps_the_binding_and_reads_a_secret_saved_without_one_as_none() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = SharedPreferencesWrappedSecretStore(context)

        store.save(WrappedSecret(byteArrayOf(1, 2), byteArrayOf(3, 4), UserPresenceBinding.DEVICE_CREDENTIAL))
        val kept = store.load()!!
        assertArrayEquals(byteArrayOf(1, 2), kept.iv)
        assertArrayEquals(byteArrayOf(3, 4), kept.ciphertext)
        assertEquals(UserPresenceBinding.DEVICE_CREDENTIAL, kept.binding)

        store.save(WrappedSecret(byteArrayOf(5), byteArrayOf(6)))
        assertNull(store.load()!!.binding)
    }
}
