// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.vauchi.data.AuthenticationRequiredException
import app.vauchi.data.OldStorageKeyProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.data.VauchiRepository
import app.vauchi.data.installFromBefore
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import app.vauchi.screenshots.ProcessSingletons
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/**
 * A storage key that needs an unlock starts Core locked (vauchi/private#580),
 * and the app shows Core's unlock screen. Asking Core for the identity fails
 * until the unlock, so the app must not take that failure for its own error
 * screen, nor let a launch-time background leg crash on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedStartLaunchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        assumeTrue("host vauchi-platform library missing", HostCoreLibrary.present())
        FakeAndroidKeyStore.install()
        ProcessSingletons.reset()
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
        val biometrics = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
        Shadow.extract<ShadowBiometricManager>(biometrics).setCanAuthenticate(true)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun anOldKeyThatNeedsAnUnlockLaunchesOnCoresUnlockScreen() {
        val provider = OldStorageKeyProvider()
        installFromBefore(context, provider)
        provider.failure = AuthenticationRequiredException("unlock")
        ProcessSingletons.useRepository(VauchiRepository(context, provider))

        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText(LOCKED_TITLE)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 30_000L

        /** `storage_lock.locked_title` in Core's English catalog. */
        const val LOCKED_TITLE = "Unlock Vauchi"
    }
}
