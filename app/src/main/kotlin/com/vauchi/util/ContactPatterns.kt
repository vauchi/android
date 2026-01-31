// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

/**
 * Pure utility functions for detecting and converting contact field values.
 * No Android or UniFFI dependencies — all functions are testable with plain JUnit.
 */
object ContactPatterns {

    enum class DetectedType { URL, EMAIL, PHONE, UNKNOWN }

    /** Returns true when the value looks like an HTTP(S) URL. */
    fun isLikelyUrl(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }

    /** Returns true when the value looks like an email address. */
    fun isLikelyEmail(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.contains("@") && trimmed.contains(".") && !trimmed.contains(" ")
    }

    /** Returns true when the value looks like a phone number (≥7 digits, valid chars). */
    fun isLikelyPhone(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.count { it.isDigit() } >= 7 &&
            trimmed.all { it.isDigit() || it in " -+()./" }
    }

    /** Classifies a value into URL, EMAIL, PHONE, or UNKNOWN. */
    fun detectValueType(value: String): DetectedType {
        return when {
            isLikelyUrl(value) -> DetectedType.URL
            isLikelyEmail(value) -> DetectedType.EMAIL
            isLikelyPhone(value) -> DetectedType.PHONE
            else -> DetectedType.UNKNOWN
        }
    }

    /** Strips a leading '@' from a username. */
    fun normalizeUsername(value: String): String = value.trimStart('@')

    /**
     * Builds a profile URL for the given social network and username.
     * Returns null for unsupported networks.
     */
    fun buildSocialProfileUrl(network: String, username: String): String? {
        val normalized = normalizeUsername(username)
        return when (network.lowercase()) {
            "twitter", "x" -> "https://twitter.com/$normalized"
            "github" -> "https://github.com/$normalized"
            "linkedin" -> "https://linkedin.com/in/$normalized"
            "instagram" -> "https://instagram.com/$normalized"
            else -> null
        }
    }

    /**
     * Normalizes a website value into an HTTPS URL string.
     * Returns null for values with unknown or blocked schemes.
     */
    fun normalizeWebsiteUrl(value: String): String? {
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.contains("://") -> null // Unknown scheme
            else -> "https://$value"
        }
    }
}
