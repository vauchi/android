// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.vauchi.data.VauchiPreferences
import app.vauchi.screenshots.FakeAndroidKeyStore
import app.vauchi.screenshots.HostCoreLibrary
import app.vauchi.screenshots.ProcessSingletons
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/**
 * A fresh install on a phone without a screen lock stops on Device Lock
 * Required. Once the user sets a lock and taps Retry, the app starts over and
 * reaches onboarding; it used to ask Core for an identity that does not exist
 * yet and show "Identity not found" (vauchi/private#702).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [HostCoreLibrary.SDK])
class DeviceLockRetryLaunchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        HostCoreLibrary.assumePresentInSharedSandbox()
        FakeAndroidKeyStore.install()
        ProcessSingletons.reset()
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        setDeviceLock(false)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun retryAfterSettingADeviceLockOnAFreshInstallReachesOnboarding() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(DEVICE_LOCK_TITLE)

        setDeviceLock(true)
        composeRule.onNode(hasTestTag("error.retry")).performClick()

        waitForText(WELCOME_SUBTITLE)
    }

    private fun setDeviceLock(present: Boolean) {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(present)
        val biometrics = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
        Shadow.extract<ShadowBiometricManager>(biometrics).setCanAuthenticate(present)
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 30_000L

        /** `auth.device_lock_required` in the English catalog. */
        const val DEVICE_LOCK_TITLE = "Device Lock Required"

        /** `onboarding.welcome_subtitle` in Core's English catalog. */
        const val WELCOME_SUBTITLE = "Your contact card, shared in person and kept up to date."
    }
}
