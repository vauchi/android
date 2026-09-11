// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.biometrics.BiometricManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.vauchi.MainActivity
import app.vauchi.data.VauchiPreferences
import app.vauchi.data.VauchiRepository
import app.vauchi.util.LocalizationManager
import app.vauchi.util.ThemeManager
import org.junit.After
import org.junit.Assert.assertTrue
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
import java.io.File
import java.security.MessageDigest

/**
 * Drives the real Core engine through [MainActivity] on the host JVM and
 * writes one PNG per visited screen under `build/screenshots/`, so CI can
 * publish what the app actually renders without a device or emulator.
 *
 * Walk one starts cold and follows the context bar's primary action through
 * onboarding; walk two starts with `reset_for_testing` (Core seeds the
 * identity) and visits every persistent-bar destination plus the
 * secondary-actions overlay.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class ScreenWalkScreenshotTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val written = mutableListOf<File>()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        resetProcessSingletons()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Shared preferences outlive the per-test data directory; a stale
        // encrypted storage-key blob paired with fresh fake keystore keys
        // would route the launch into key-invalidated recovery.
        context.getSharedPreferences(VauchiPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setIsDeviceSecure(true)
        val biometrics = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
        Shadow.extract<ShadowBiometricManager>(biometrics).setCanAuthenticate(true)
        outputDir.mkdirs()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun onboardingFlowRendersEachStep() {
        launch(resetForTesting = false)
        waitForPresentation()

        // The display name is the first input onboarding asks for; later
        // optional inputs (custom group, contact info) stay untouched.
        var displayNameEntered = false
        for (step in 1..MAX_ONBOARDING_STEPS) {
            capture(step, "onboarding-${surfaceSlug()}")
            if (persistentBarAppears()) break
            val emptyField = composeRule.onAllNodes(hasSetTextAction()).firstOrNull { it.textOrEmpty().isEmpty() }
            if (emptyField != null && !displayNameEntered) {
                displayNameEntered = true
                emptyField.performTextInput(DISPLAY_NAME)
                composeRule.waitForIdle()
                capture(step, "onboarding-${surfaceSlug()}-filled")
            }
            val primary = composeRule.onAllNodes(hasTestTag(PRIMARY_TAG) and isEnabled()).firstOrNull() ?: break
            clickAndSettle(primary)
        }

        assertTrue("onboarding walk wrote no screenshots", written.isNotEmpty())
    }

    @Test
    fun homeDestinationsRenderDistinctScreens() {
        launch(resetForTesting = true)
        waitForSeededHome()
        var index = 1
        capture(index++, "after-seed")
        composeRule.onAllNodes(hasTestTag(SECONDARY_TAG) and isEnabled()).firstOrNull()?.let {
            clickAndSettle(it)
            capture(index++, "secondary-actions")
            dismissOverlay()
        }

        val digests = mutableSetOf<String>()
        for (destination in 0 until destinationCount()) {
            val node = openDestination(destination) ?: break
            val slug = node.labelOrEmpty().ifBlank { "destination-$destination" }.slugify()
            clickAndSettle(node)
            digests += sha256(capture(index++, slug))
        }

        assertTrue("home walk wrote no screenshots", written.isNotEmpty())
        assertTrue("expected at least 3 distinct destination captures, got ${digests.size}", digests.size >= 3)
    }

    /**
     * Destinations come from the persistent bar when Core publishes one and
     * from the navigation launcher overlay otherwise.
     */
    private fun destinationCount(): Int {
        persistentBarItems().size.takeIf { it > 0 }?.let { return it }
        openNavigationOverlay() ?: return 0
        return overlayActions().size.also { dismissOverlay() }
    }

    private fun openDestination(destination: Int): SemanticsNodeInteraction? {
        composeRule.onAllNodes(hasTestTag("$NAVBAR_TAG_PREFIX$destination")).firstOrNull()?.let { return it }
        openNavigationOverlay() ?: return null
        return composeRule.onAllNodes(hasTestTag("$OVERLAY_TAG_PREFIX$destination")).firstOrNull()
    }

    private fun openNavigationOverlay(): SemanticsNodeInteraction? {
        if (overlayActions().isNotEmpty()) return composeRule.onRoot()
        val launcher = composeRule.onAllNodes(hasTestTag(NAVIGATION_TAG) and isEnabled()).firstOrNull() ?: return null
        clickAndSettle(launcher)
        return launcher
    }

    private fun dismissOverlay() {
        composeRule.onAllNodes(hasTestTag(SCRIM_TAG)).firstOrNull()?.let(::clickAndSettle)
    }

    private fun overlayActions() =
        composeRule
            .onAllNodes(
                SemanticsMatcher("overlay action") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(OVERLAY_TAG_PREFIX) == true
                },
            ).fetchSemanticsNodes()

    private fun launch(resetForTesting: Boolean) {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                if (resetForTesting) putExtra("reset_for_testing", true)
            }
        scenario = ActivityScenario.launch(intent)
        composeRule.waitForIdle()
    }

    private fun waitForPresentation() =
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag(PRIMARY_TAG)).fetchSemanticsNodes().isNotEmpty()
        }

    /** The bar arrives once Core reports the identity, one commit after the last onboarding step. */
    private fun persistentBarAppears(): Boolean =
        runCatching {
            composeRule.waitUntil(timeoutMillis = STEP_SETTLE_TIMEOUT_MS) { persistentBarItems().isNotEmpty() }
        }.isSuccess

    /**
     * `reset_for_testing` replays onboarding one surface revision at a time;
     * the seed is done when the tree stops changing between two polls.
     */
    private fun waitForSeededHome() {
        try {
            composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
                composeRule.onAllNodes(hasTestTag(NAVIGATION_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            var previous = ""
            composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MS) {
                Thread.sleep(QUIESCENCE_POLL_MS)
                val current = composeRule.onRoot().printToString()
                (current == previous).also { previous = current }
            }
        } finally {
            File(outputDir, "00-after-launch.txt").writeText(composeRule.onRoot().printToString())
        }
    }

    private fun persistentBarItems() =
        composeRule
            .onAllNodes(
                SemanticsMatcher("persistent bar item") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(NAVBAR_TAG_PREFIX) == true
                },
            ).fetchSemanticsNodes()

    /**
     * Core answers a click off the main thread, so the settled surface is
     * the first tree that differs from the one the click landed on. A click
     * Core ignores (a disabled step, an already-open overlay) times out
     * quietly and the walk carries on from the unchanged surface.
     */
    private fun clickAndSettle(node: SemanticsNodeInteraction) {
        val before = composeRule.onRoot().printToString()
        node.performClick()
        composeRule.waitForIdle()
        runCatching {
            composeRule.waitUntil(timeoutMillis = STEP_SETTLE_TIMEOUT_MS) {
                composeRule.onRoot().printToString() != before
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(
        index: Int,
        slug: String,
    ): File {
        composeRule.waitForIdle()
        val file = File(outputDir, "%02d-%s.png".format(index, slug))
        File(outputDir, "%02d-%s.txt".format(index, slug)).writeText(composeRule.onRoot().printToString())
        val bitmap = drawWindow()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("screenshot not written: $file", file.length() > 0)
        written += file
        return file
    }

    /**
     * Draws the decor view into a bitmap instead of `captureToImage()`: the
     * Compose capture waits on a hardware frame-commit callback that never
     * fires under Robolectric (robolectric/robolectric#8071), while a direct
     * software draw renders through the native Skia canvas.
     */
    private fun drawWindow(): Bitmap {
        lateinit var bitmap: Bitmap
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun surfaceSlug(): String {
        val heading =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .firstOrNull()
                ?.textOrEmpty()
        return heading?.slugify()?.ifBlank { null } ?: "step"
    }

    private fun SemanticsNodeInteractionCollection.firstOrNull(): SemanticsNodeInteraction? =
        if (fetchSemanticsNodes().isEmpty()) null else onFirst()

    private fun SemanticsNodeInteractionCollection.firstOrNull(
        predicate: (SemanticsNodeInteraction) -> Boolean,
    ): SemanticsNodeInteraction? = fetchSemanticsNodes().indices.map { this[it] }.firstOrNull(predicate)

    private fun SemanticsNodeInteraction.textOrEmpty(): String {
        val config = fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: ""
    }

    private fun SemanticsNodeInteraction.labelOrEmpty(): String {
        val config = fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: ""
    }

    private fun String.slugify(): String =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /**
     * The repository, theme, and localization singletons outlive a Robolectric
     * test's application instance; clear them so the second walk gets a fresh
     * engine on its own data directory.
     */
    private fun resetProcessSingletons() {
        listOf(VauchiRepository::class.java, LocalizationManager::class.java, ThemeManager::class.java).forEach { cls ->
            cls.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private companion object {
        const val PRIMARY_TAG = "contextbar.primary"
        const val SECONDARY_TAG = "contextbar.secondary"
        const val NAVIGATION_TAG = "contextbar.navigation"
        const val OVERLAY_TAG_PREFIX = "overlay.action."
        const val SCRIM_TAG = "overlay.scrim"
        const val NAVBAR_TAG_PREFIX = "navbar.item."
        const val DISPLAY_NAME = "Test User"
        const val MAX_ONBOARDING_STEPS = 8
        const val MAX_SLUG_LENGTH = 40
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val STEP_SETTLE_TIMEOUT_MS = 3_000L
        const val QUIESCENCE_POLL_MS = 1_000L

        val outputDir: File =
            System.getProperty("vauchi.screenshotDir")?.let(::File)
                ?: File("build/screenshots")
    }
}
