// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import com.vauchi.util.ContactPatterns.DetectedType
import org.junit.Assert.*
import org.junit.Test

class ContactPatternsTest {

    // --- isLikelyUrl ---

    @Test
    fun `isLikelyUrl returns true for https URL`() {
        assertTrue(ContactPatterns.isLikelyUrl("https://example.com"))
    }

    @Test
    fun `isLikelyUrl returns true for http URL`() {
        assertTrue(ContactPatterns.isLikelyUrl("http://example.com"))
    }

    @Test
    fun `isLikelyUrl returns false for plain text`() {
        assertFalse(ContactPatterns.isLikelyUrl("example.com"))
    }

    @Test
    fun `isLikelyUrl returns false for email`() {
        assertFalse(ContactPatterns.isLikelyUrl("user@example.com"))
    }

    @Test
    fun `isLikelyUrl trims whitespace`() {
        assertTrue(ContactPatterns.isLikelyUrl("  https://example.com  "))
    }

    // --- isLikelyEmail ---

    @Test
    fun `isLikelyEmail returns true for standard email`() {
        assertTrue(ContactPatterns.isLikelyEmail("user@example.com"))
    }

    @Test
    fun `isLikelyEmail returns false for text without at sign`() {
        assertFalse(ContactPatterns.isLikelyEmail("just text"))
    }

    @Test
    fun `isLikelyEmail returns false for text with space`() {
        assertFalse(ContactPatterns.isLikelyEmail("user @example.com"))
    }

    @Test
    fun `isLikelyEmail returns false without dot`() {
        assertFalse(ContactPatterns.isLikelyEmail("user@localhost"))
    }

    // --- isLikelyPhone ---

    @Test
    fun `isLikelyPhone returns true for international number`() {
        assertTrue(ContactPatterns.isLikelyPhone("+1 555-123-4567"))
    }

    @Test
    fun `isLikelyPhone returns true for local number`() {
        assertTrue(ContactPatterns.isLikelyPhone("5551234567"))
    }

    @Test
    fun `isLikelyPhone returns false for too few digits`() {
        assertFalse(ContactPatterns.isLikelyPhone("123"))
    }

    @Test
    fun `isLikelyPhone returns false for text with letters`() {
        assertFalse(ContactPatterns.isLikelyPhone("call me at 555"))
    }

    @Test
    fun `isLikelyPhone accepts parentheses and dots`() {
        assertTrue(ContactPatterns.isLikelyPhone("(555) 123.4567"))
    }

    // --- detectValueType ---

    @Test
    fun `detectValueType identifies URL`() {
        assertEquals(DetectedType.URL, ContactPatterns.detectValueType("https://example.com"))
    }

    @Test
    fun `detectValueType identifies email`() {
        assertEquals(DetectedType.EMAIL, ContactPatterns.detectValueType("user@example.com"))
    }

    @Test
    fun `detectValueType identifies phone`() {
        assertEquals(DetectedType.PHONE, ContactPatterns.detectValueType("+1 555-123-4567"))
    }

    @Test
    fun `detectValueType returns UNKNOWN for plain text`() {
        assertEquals(DetectedType.UNKNOWN, ContactPatterns.detectValueType("hello world"))
    }

    // --- normalizeUsername ---

    @Test
    fun `normalizeUsername strips leading at sign`() {
        assertEquals("johndoe", ContactPatterns.normalizeUsername("@johndoe"))
    }

    @Test
    fun `normalizeUsername leaves plain username unchanged`() {
        assertEquals("johndoe", ContactPatterns.normalizeUsername("johndoe"))
    }

    // --- buildSocialProfileUrl ---

    @Test
    fun `buildSocialProfileUrl returns twitter URL`() {
        assertEquals("https://twitter.com/johndoe", ContactPatterns.buildSocialProfileUrl("Twitter", "@johndoe"))
    }

    @Test
    fun `buildSocialProfileUrl returns github URL`() {
        assertEquals("https://github.com/johndoe", ContactPatterns.buildSocialProfileUrl("GitHub", "johndoe"))
    }

    @Test
    fun `buildSocialProfileUrl returns linkedin URL`() {
        assertEquals("https://linkedin.com/in/johndoe", ContactPatterns.buildSocialProfileUrl("LinkedIn", "johndoe"))
    }

    @Test
    fun `buildSocialProfileUrl returns instagram URL`() {
        assertEquals("https://instagram.com/johndoe", ContactPatterns.buildSocialProfileUrl("Instagram", "@johndoe"))
    }

    @Test
    fun `buildSocialProfileUrl returns null for unknown network`() {
        assertNull(ContactPatterns.buildSocialProfileUrl("MySpace", "johndoe"))
    }

    @Test
    fun `buildSocialProfileUrl handles X as twitter alias`() {
        assertEquals("https://twitter.com/johndoe", ContactPatterns.buildSocialProfileUrl("X", "johndoe"))
    }

    // --- normalizeWebsiteUrl ---

    @Test
    fun `normalizeWebsiteUrl passes through https URL`() {
        assertEquals("https://example.com", ContactPatterns.normalizeWebsiteUrl("https://example.com"))
    }

    @Test
    fun `normalizeWebsiteUrl passes through http URL`() {
        assertEquals("http://example.com", ContactPatterns.normalizeWebsiteUrl("http://example.com"))
    }

    @Test
    fun `normalizeWebsiteUrl prepends https to bare domain`() {
        assertEquals("https://example.com", ContactPatterns.normalizeWebsiteUrl("example.com"))
    }

    @Test
    fun `normalizeWebsiteUrl returns null for unknown scheme`() {
        assertNull(ContactPatterns.normalizeWebsiteUrl("ftp://example.com"))
    }
}
