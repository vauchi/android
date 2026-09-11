// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import app.vauchi.ui.presentation.PresentationScreen
import app.vauchi.ui.presentation.PresentationScreenActions
import app.vauchi.ui.theme.LocalStatusColors
import app.vauchi.ui.theme.StatusColors
import app.vauchi.ui.theme.Typography
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * Replays Core's screen catalogue (`screen_catalog_v1.json`, one command
 * batch per app screen) through the real Compose renderer and writes one
 * PNG per screen and variant under `build/screen-catalog/`. No engine, no
 * device, no navigation: each batch already carries the whole surface, so
 * the parser and reducer prepare the state and [PresentationScreen] draws
 * it exactly as the app would.
 *
 * Skips itself unless gradle passes `-Pvauchi.screenCatalog=<path>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class ScreenCatalogRenderTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var entries: List<ScreenCatalogEntry>
    private var frame by mutableStateOf<Frame?>(null)

    @Before
    fun setUp() {
        val catalog = catalogFile
        assumeTrue("no catalogue: pass -Pvauchi.screenCatalog=<screen_catalog_v1.json>", catalog != null && catalog.isFile)
        entries = ScreenCatalog.decode(checkNotNull(catalog).readText())
        outputDir.mkdirs()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                frame?.let { current ->
                    key(current.entry.codeId, current.variant) {
                        CatalogFrame(current)
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun everyCatalogueScreenRendersInEachVariant() {
        assertTrue("catalogue lists no screens", entries.isNotEmpty())
        val digests = mutableSetOf<String>()
        for (entry in entries) {
            for (variant in Variant.entries) {
                frame = Frame(entry, variant)
                composeRule.waitForIdle()
                digests += sha256(capture(File(outputDir, "${entry.codeId}${variant.suffix}.png")))
            }
        }
        assertTrue("expected at least 3 distinct captures, got ${digests.size}", digests.size >= 3)
    }

    @Composable
    private fun CatalogFrame(frame: Frame) {
        val entry = frame.entry
        val configuration =
            Configuration(LocalConfiguration.current).apply {
                fontScale = frame.variant.fontScale
                uiMode =
                    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (frame.variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        val density = Density(LocalDensity.current.density, frame.variant.fontScale)
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalDensity provides density,
            LocalStatusColors provides STATUS_COLORS,
        ) {
            MaterialTheme(
                colorScheme = if (frame.variant.dark) darkColorScheme() else lightColorScheme(),
                typography = Typography,
            ) {
                PresentationScreen(
                    state = entry.state,
                    profile = entry.profile,
                    activeSurfaceId = entry.activeSurfaceId,
                    reducedMotion = true,
                    focusedBindingId = null,
                    onFocusedBinding = { _, _ -> },
                    actions = INERT_ACTIONS,
                )
            }
        }
    }

    private fun capture(file: File): File {
        val bitmap = drawWindow()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("screenshot not written: $file", file.length() > 0)
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

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private data class Frame(
        val entry: ScreenCatalogEntry,
        val variant: Variant,
    )

    private enum class Variant(
        val suffix: String,
        val dark: Boolean,
        val fontScale: Float,
    ) {
        Light("", dark = false, fontScale = 1f),
        Dark(".dark", dark = true, fontScale = 1f),
        Large(".large", dark = false, fontScale = 1.3f),
    }

    private companion object {
        /** The app's fallback status palette (`Theme.kt`) — the theme engine needs Core, which this test does not load. */
        val STATUS_COLORS =
            StatusColors(
                success = Color(0xFF2E7D32),
                warning = Color(0xFFF9A825),
                info = Color(0xFF1976D2),
            )

        val INERT_ACTIONS =
            PresentationScreenActions(
                onEvent = { _, _ -> },
                onSurfaceActivated = {},
                onCameraPermissionDenied = {},
                onDismissOverlay = {},
            )

        val catalogFile: File? = System.getProperty("vauchi.screenCatalog")?.let(::File)

        val outputDir: File =
            System.getProperty("vauchi.screenCatalogDir")?.let(::File)
                ?: File("build/screen-catalog")
    }
}
