// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.deeplink

import android.net.Uri

/**
 * Result of parsing a deep link URI.
 */
sealed class DeepLinkResult {
    /** A valid exchange deep link that requires user consent before processing. */
    data class ExchangePending(
        val exchangePayload: String,
    ) : DeepLinkResult()

    /** The deep link URI was invalid or unsupported. */
    data class Invalid(
        val reason: String,
    ) : DeepLinkResult()
}

/**
 * State of a deep link consent gate.
 */
enum class ConsentState {
    /** Waiting for user to grant or deny consent. */
    PENDING,

    /** User granted consent — exchange may proceed. */
    GRANTED,

    /** User denied consent — exchange must NOT proceed. */
    DENIED,
}

/**
 * Handles incoming `vauchi://` deep links with a mandatory consent gate.
 *
 * Deep links are NEVER auto-processed. The user must explicitly confirm
 * before any exchange payload is forwarded for processing.
 *
 * Supported paths:
 *   vauchi://exchange/<payload>
 */
class DeepLinkHandler {
    private var _consentState: ConsentState = ConsentState.PENDING
    val consentState: ConsentState get() = _consentState

    private var _pendingPayload: String? = null
    val pendingPayload: String? get() = _pendingPayload

    private var _exchangeProcessed: Boolean = false
    val exchangeProcessed: Boolean get() = _exchangeProcessed

    /**
     * Parse an incoming deep link URI.
     *
     * Returns [DeepLinkResult.ExchangePending] if the URI is a valid exchange link.
     * The exchange is NOT processed — it is held pending until [grantConsent] is called.
     *
     * Returns [DeepLinkResult.Invalid] if the URI is malformed or unsupported.
     */
    fun handleDeepLink(uri: Uri): DeepLinkResult {
        val scheme = uri.scheme
        if (scheme != "vauchi") {
            return DeepLinkResult.Invalid("Unsupported scheme: $scheme")
        }

        val host = uri.host
        if (host != "exchange") {
            return DeepLinkResult.Invalid("Unsupported path: $host")
        }

        val payload = uri.pathSegments.firstOrNull()
        if (payload.isNullOrBlank()) {
            return DeepLinkResult.Invalid("Missing exchange payload")
        }

        // Store payload but do NOT process — consent required
        _pendingPayload = payload
        _consentState = ConsentState.PENDING
        _exchangeProcessed = false

        return DeepLinkResult.ExchangePending(payload)
    }

    /**
     * Grant consent to process the pending exchange.
     *
     * Returns the exchange payload if consent is granted and a payload is pending.
     * Returns null if there is no pending payload.
     */
    fun grantConsent(): String? {
        _consentState = ConsentState.GRANTED
        val payload = _pendingPayload
        if (payload != null) {
            _exchangeProcessed = true
        }
        return payload
    }

    /**
     * Deny consent — the pending exchange is discarded.
     */
    fun denyConsent() {
        _consentState = ConsentState.DENIED
        _pendingPayload = null
        _exchangeProcessed = false
    }

    /**
     * Reset the handler state (e.g., after an exchange completes or is dismissed).
     */
    fun reset() {
        _consentState = ConsentState.PENDING
        _pendingPayload = null
        _exchangeProcessed = false
    }
}
