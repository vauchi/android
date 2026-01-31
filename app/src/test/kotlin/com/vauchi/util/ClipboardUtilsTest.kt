// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
    fun `copyWithAutoClear sets clipboard text immediately`() = runTest {
        ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("sensitive data", clip!!.getItemAt(0).text.toString())
    }

    @Test
    fun `copyWithAutoClear clears clipboard after 30 seconds`() = runTest {
        ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

        // Verify text is set
        assertEquals("sensitive data", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        // Advance past the 30-second delay
        advanceTimeBy(31_000L)

        // Clipboard should be cleared (empty string)
        val currentText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("", currentText)
    }

    @Test
    fun `copyWithAutoClear does not clear if clipboard was changed`() = runTest {
        ClipboardUtils.copyWithAutoClear(context, this, "sensitive data")

        // Simulate user copying something else
        ClipboardUtils.copy(context, "other data")

        // Advance past the 30-second delay
        advanceTimeBy(31_000L)

        // Clipboard should still have the other data (not cleared)
        assertEquals("other data", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `copy overwrites previous clipboard content`() {
        ClipboardUtils.copy(context, "first")
        ClipboardUtils.copy(context, "second")

        assertEquals("second", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
