// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import uniffi.vauchi_mobile.MobileContact
import uniffi.vauchi_mobile.MobileContactCard
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ContactActionsTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockContact: MobileContact

    @Mock
    private lateinit var mockContactCard: MobileContactCard

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun `test format contact name with display name`() {
        val displayName = "John Doe"
        whenever(mockContact.displayName).thenReturn(displayName)

        val formattedName = ContactActions.formatContactName(mockContact)
        assertEquals(displayName, formattedName)
    }

    @Test
    fun `test format contact name when empty`() {
        whenever(mockContact.displayName).thenReturn("")
        
        val formattedName = ContactActions.formatContactName(mockContact)
        assertEquals("Unknown Contact", formattedName)
    }

    @Test
    fun `test get contact initials`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val initials = ContactActions.getContactInitials(mockContact)
        assertEquals("JD", initials)
    }

    @Test
    fun `test get contact initials with single name`() {
        whenever(mockContact.displayName).thenReturn("John")
        
        val initials = ContactActions.getContactInitials(mockContact)
        assertEquals("J", initials)
    }

    @Test
    fun `test get contact initials with empty name`() {
        whenever(mockContact.displayName).thenReturn("")
        
        val initials = ContactActions.getContactInitials(mockContact)
        assertEquals("?", initials)
    }

    @Test
    fun `test get contact avatar color`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val color = ContactActions.getContactAvatarColor(mockContact)
        assertNotNull(color)
        // Should return a consistent color based on name hash
        assertEquals(color, ContactActions.getContactAvatarColor(mockContact))
    }

    @Test
    fun `test get contact status text`() {
        whenever(mockContact.verified).thenReturn(true)
        
        val statusText = ContactActions.getContactStatusText(mockContact)
        assertEquals("Verified", statusText)
    }

    @Test
    fun `test get contact status text when not verified`() {
        whenever(mockContact.verified).thenReturn(false)
        
        val statusText = ContactActions.getContactStatusText(mockContact)
        assertEquals("Not Verified", statusText)
    }

    @Test
    fun `test get contact last seen when recent`() {
        // Test with recent timestamp (would need to mock time)
        whenever(mockContact.lastSeen).thenReturn(1234567890u)
        
        val lastSeen = ContactActions.getContactLastSeen(mockContact)
        assertNotNull(lastSeen)
    }

    @Test
    fun `test get contact fields summary`() {
        // Test with contact card fields
        whenever(mockContactCard.fields).thenReturn(listOf())
        
        val summary = ContactActions.getContactFieldsSummary(mockContactCard)
        assertNotNull(summary)
    }

    @Test
    fun `test get contact fields summary with multiple fields`() {
        // This would require setting up mock fields
        // For now, test that method doesn't crash
        val summary = ContactActions.getContactFieldsSummary(mockContactCard)
        assertNotNull(summary)
    }

    @Test
    fun `test is contact searchable`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val searchable = ContactActions.isContactSearchable(mockContact, "john")
        assertTrue(searchable)
    }

    @Test
    fun `test is contact searchable case insensitive`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val searchable = ContactActions.isContactSearchable(mockContact, "JOHN")
        assertTrue(searchable)
    }

    @Test
    fun `test is contact searchable with partial match`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val searchable = ContactActions.isContactSearchable(mockContact, "Doe")
        assertTrue(searchable)
    }

    @Test
    fun `test is contact searchable when no match`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        
        val searchable = ContactActions.isContactSearchable(mockContact, "Jane")
        assertFalse(searchable)
    }

    @Test
    fun `test share contact text`() {
        whenever(mockContact.displayName).thenReturn("John Doe")
        whenever(mockContactCard.fields).thenReturn(listOf())
        
        val shareText = ContactActions.createShareContactText(mockContact, mockContactCard)
        assertNotNull(shareText)
        assertTrue(shareText.contains("John Doe"))
    }
}
