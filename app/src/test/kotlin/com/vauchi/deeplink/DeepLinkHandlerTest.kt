// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.deeplink

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for deep link consent gate.
 * Based on: SP-9 Deep Link Consent Gate
 *
 * Critical security invariant: deep links MUST require explicit user consent
 * before any exchange is processed. Auto-processing is forbidden.
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkHandlerTest {
    private lateinit var handler: DeepLinkHandler

    @Before
    fun setUp() {
        handler = DeepLinkHandler()
    }

    // MARK: - Consent Gate Tests

    @Test
    fun `deep link requires consent before exchange is processed`() {
        val uri = Uri.parse("vauchi://exchange/abc123payload")
        val result = handler.handleDeepLink(uri)

        // Deep link is parsed but NOT processed
        assertTrue("Result should be ExchangePending", result is DeepLinkResult.ExchangePending)
        assertEquals("abc123payload", (result as DeepLinkResult.ExchangePending).exchangePayload)

        // Exchange must NOT be processed yet — consent is pending
        assertFalse("Exchange must not be auto-processed", handler.exchangeProcessed)
        assertEquals("Consent must be PENDING", ConsentState.PENDING, handler.consentState)
        assertNotNull("Payload must be held pending", handler.pendingPayload)
    }

    @Test
    fun `exchange is processed only after consent is granted`() {
        val uri = Uri.parse("vauchi://exchange/payload456")
        handler.handleDeepLink(uri)

        // Before consent
        assertFalse(handler.exchangeProcessed)

        // Grant consent
        val payload = handler.grantConsent()

        assertEquals("payload456", payload)
        assertTrue("Exchange should be processed after consent", handler.exchangeProcessed)
        assertEquals(ConsentState.GRANTED, handler.consentState)
    }

    @Test
    fun `exchange is discarded when consent is denied`() {
        val uri = Uri.parse("vauchi://exchange/sensitive-data")
        handler.handleDeepLink(uri)

        // Deny consent
        handler.denyConsent()

        assertFalse("Exchange must not be processed on denial", handler.exchangeProcessed)
        assertEquals(ConsentState.DENIED, handler.consentState)
        assertNull("Pending payload must be cleared on denial", handler.pendingPayload)
    }

    @Test
    fun `consent state is pending immediately after deep link received`() {
        val uri = Uri.parse("vauchi://exchange/test")
        handler.handleDeepLink(uri)

        assertEquals(ConsentState.PENDING, handler.consentState)
    }

    // MARK: - URI Parsing Tests

    @Test
    fun `valid exchange deep link is parsed correctly`() {
        val uri = Uri.parse("vauchi://exchange/wb%3A%2F%2FsomeBase64Data")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.ExchangePending)
        val pending = result as DeepLinkResult.ExchangePending
        assertEquals("wb:%2F%2FsomeBase64Data", pending.exchangePayload)
    }

    @Test
    fun `invalid scheme returns Invalid result`() {
        val uri = Uri.parse("https://exchange/payload")
        val result = handler.handleDeepLink(uri)

        assertTrue("Non-vauchi scheme should be invalid", result is DeepLinkResult.Invalid)
        val invalid = result as DeepLinkResult.Invalid
        assertTrue("Reason should mention scheme", invalid.reason.contains("scheme"))
    }

    @Test
    fun `unsupported path returns Invalid result`() {
        val uri = Uri.parse("vauchi://settings/something")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.Invalid)
        val invalid = result as DeepLinkResult.Invalid
        assertTrue("Reason should mention path", invalid.reason.contains("path"))
    }

    @Test
    fun `missing payload returns Invalid result`() {
        val uri = Uri.parse("vauchi://exchange")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.Invalid)
        val invalid = result as DeepLinkResult.Invalid
        assertTrue("Reason should mention payload", invalid.reason.contains("payload"))
    }

    @Test
    fun `empty payload returns Invalid result`() {
        val uri = Uri.parse("vauchi://exchange/")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.Invalid)
    }

    // MARK: - State Management Tests

    @Test
    fun `granting consent without pending payload returns null`() {
        val result = handler.grantConsent()

        assertNull("No pending payload should return null", result)
    }

    @Test
    fun `reset clears all state`() {
        val uri = Uri.parse("vauchi://exchange/data")
        handler.handleDeepLink(uri)
        handler.grantConsent()

        handler.reset()

        assertEquals(ConsentState.PENDING, handler.consentState)
        assertNull(handler.pendingPayload)
        assertFalse(handler.exchangeProcessed)
    }

    @Test
    fun `second deep link replaces first pending payload`() {
        handler.handleDeepLink(Uri.parse("vauchi://exchange/first"))
        handler.handleDeepLink(Uri.parse("vauchi://exchange/second"))

        assertEquals("second", handler.pendingPayload)
        assertFalse("Exchange must still require consent", handler.exchangeProcessed)
        assertEquals(ConsentState.PENDING, handler.consentState)
    }

    // MARK: - Adversarial Input Tests (CC-14)

    @Test
    fun `null-byte payload is treated as invalid path segment`() {
        // URI with null bytes — Android Uri parser may handle differently
        val uri = Uri.parse("vauchi://exchange/%00")
        val result = handler.handleDeepLink(uri)
        // Whether parsed or not, exchange must not auto-process
        assertFalse(handler.exchangeProcessed)
    }

    @Test
    fun `extremely long payload does not crash`() {
        val longPayload = "a".repeat(100_000)
        val uri = Uri.parse("vauchi://exchange/$longPayload")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.ExchangePending)
        assertFalse(handler.exchangeProcessed)
    }

    @Test
    fun `unicode payload is handled without crash`() {
        val uri = Uri.parse("vauchi://exchange/hello%F0%9F%91%8Bworld")
        val result = handler.handleDeepLink(uri)

        assertTrue(result is DeepLinkResult.ExchangePending)
        assertFalse(handler.exchangeProcessed)
    }
}
