// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClipboardUtilsTest {
    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    @Test
    fun `copy sets clipboard text`() {
        ClipboardUtils.copy(context, "hello world")

        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals("hello world", clip.getItemAt(0).text.toString())
    }

    @Test
    fun `copy uses provided label`() {
        ClipboardUtils.copy(context, "test", label = "MyLabel")

        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("MyLabel", clip!!.description.label.toString())
    }

    @Test
    fun `copy uses default Vauchi label`() {
        ClipboardUtils.copy(context, "test")

        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("Vauchi", clip!!.description.label.toString())
    }

    @Test
    fun `copyWithAutoClear sets clipboard text immediately`() =
        runTest {
            ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

            val clip = clipboard.primaryClip
            assertNotNull(clip)
            assertEquals("sensitive data", clip!!.getItemAt(0).text.toString())
        }

    @Test
    fun `copyWithAutoClear clears clipboard after 30 seconds`() =
        runTest {
            ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

            // Verify text is set
            assertEquals(
                "sensitive data",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )

            // Advance past the 30-second delay
            advanceTimeBy(31_000L)

            // Clipboard should be cleared (empty string)
            val currentText =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
            assertEquals("", currentText)
        }

    @Test
    fun `copyWithAutoClear does not clear if clipboard was changed`() =
        runTest {
            ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

            // Simulate user copying something else
            ClipboardUtils.copy(context, "other data")

            // Advance past the 30-second delay
            advanceTimeBy(31_000L)

            // Clipboard should still have the other data (not cleared)
            assertEquals(
                "other data",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }

    @Test
    fun `copy overwrites previous clipboard content`() {
        ClipboardUtils.copy(context, "first")
        ClipboardUtils.copy(context, "second")

        assertEquals(
            "second",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `copyWithAutoClear sets EXTRA_IS_SENSITIVE on Android 13+`() =
        runTest {
            ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

            val description = clipboard.primaryClip?.description
            assertNotNull(description)
            val extras = description!!.extras
            assertNotNull(
                "clip description must carry extras on Android 13+",
                extras,
            )
            assertTrue(
                "EXTRA_IS_SENSITIVE must be true so the system clipboard preview hides the content",
                extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
            )
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `copyWithAutoClear skips EXTRA_IS_SENSITIVE on pre-Android 13`() =
        runTest {
            ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

            // Pre-Tiramisu the API doesn't exist; we must not call it. The
            // helper should still set the primary clip with the text payload.
            val clip = clipboard.primaryClip
            assertNotNull(clip)
            assertEquals("sensitive data", clip!!.getItemAt(0).text.toString())
        }
}
