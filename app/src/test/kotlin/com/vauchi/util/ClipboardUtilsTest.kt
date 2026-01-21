package com.vauchi.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClipboardUtilsTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockClipboardManager: ClipboardManager

    @Mock
    private lateinit var mockClipData: ClipData

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockContext.getSystemService(Context.CLIPBOARD_SERVICE))
            .thenReturn(mockClipboardManager)
    }

    @Test
    fun `test copy to clipboard`() {
        val testText = "Test text to copy"
        
        ClipboardUtils.copyToClipboard(mockContext, testText)
        
        verify(mockClipboardManager).setPrimaryClip(any<ClipData>())
    }

    @Test
    fun `test get from clipboard when text exists`() {
        val testText = "Text from clipboard"
        whenever(mockClipboardManager.primaryClip).thenReturn(mockClipData)
        whenever(mockClipData.getItemCount()).thenReturn(1)
        whenever(mockClipData.getItemAt(0)).thenReturn(mock(ClipData.Item::class.java))
        whenever(mockClipData.getItemAt(0).text).thenReturn(testText)
        
        val result = ClipboardUtils.getFromClipboard(mockContext)
        
        assertNotNull(result)
        assertEquals(testText, result)
    }

    @Test
    fun `test get from clipboard when empty`() {
        whenever(mockClipboardManager.primaryClip).thenReturn(null)
        
        val result = ClipboardUtils.getFromClipboard(mockContext)
        
        assertEquals(null, result)
    }

    @Test
    fun `test has clipboard text when true`() {
        whenever(mockClipboardManager.primaryClip).thenReturn(mockClipData)
        whenever(mockClipData.getItemCount()).thenReturn(1)
        whenever(mockClipData.getItemAt(0)).thenReturn(mock(ClipData.Item::class.java))
        whenever(mockClipData.getItemAt(0).text).thenReturn("Some text")
        
        val hasText = ClipboardUtils.hasClipboardText(mockContext)
        
        assertTrue(hasText)
    }

    @Test
    fun `test has clipboard text when false`() {
        whenever(mockClipboardManager.primaryClip).thenReturn(null)
        
        val hasText = ClipboardUtils.hasClipboardText(mockContext)
        
        assertEquals(false, hasText)
    }
}