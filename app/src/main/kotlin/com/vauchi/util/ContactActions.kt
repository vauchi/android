// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import uniffi.vauchi_mobile.MobileContactField
import uniffi.vauchi_mobile.MobileFieldType

/**
 * Utility object for opening contact fields in external applications.
 * 
 * URL safety validation and social network URLs are now handled by vauchi-core.
 * Use the UniFFI functions: isSafeUrl(), isAllowedScheme(), isBlockedScheme()
 * and VauchiMobile.getProfileUrl() for social networks (40+ networks supported).
 */
object ContactActions {

    /**
     * Opens a contact field in the appropriate external application.
     *
     * @param context The Android context
     * @param field The contact field to open
     * @return true if the field was opened, false if it was copied to clipboard
     */
    fun openField(context: Context, field: MobileContactField): Boolean {
        val uri = fieldToUri(field)

        if (uri == null) {
            copyToClipboard(context, field.value, field.label)
            return false
        }

        // Security check: validate URI using vauchi-core
        if (!uniffi.vauchi_mobile.isSafeUrl(uri.toString())) {
            Toast.makeText(context, "Cannot open: blocked for security", Toast.LENGTH_SHORT).show()
            copyToClipboard(context, field.value, field.label)
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                Toast.makeText(context, "No app available to open this", Toast.LENGTH_SHORT).show()
                copyToClipboard(context, field.value, field.label)
                false
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open: ${e.message}", Toast.LENGTH_SHORT).show()
            copyToClipboard(context, field.value, field.label)
            false
        }
    }

    /**
     * Converts a contact field to a URI.
     */
    private fun fieldToUri(field: MobileContactField): Uri? {
        val value = field.value.trim()
        if (value.isEmpty()) return null

        return when (field.fieldType) {
            MobileFieldType.PHONE -> Uri.parse("tel:$value")
            MobileFieldType.EMAIL -> Uri.parse("mailto:$value")
            MobileFieldType.WEBSITE -> websiteToUri(value)
            MobileFieldType.ADDRESS -> Uri.parse("geo:0,0?q=${Uri.encode(value)}")
            MobileFieldType.SOCIAL -> socialToUri(field.label, value)
            MobileFieldType.CUSTOM -> detectAndConvert(value)
        }
    }

    /**
     * Converts a website value to a URI, adding https:// if needed.
     * Uses vauchi-core for scheme validation; URL normalization delegated to [ContactPatterns].
     */
    private fun websiteToUri(value: String): Uri? {
        // Check for blocked schemes using vauchi-core
        val scheme = value.substringBefore("://").lowercase()
        if (scheme.isNotEmpty() && value.contains("://")) {
            if (uniffi.vauchi_mobile.isBlockedScheme(scheme)) return null
        }

        return ContactPatterns.normalizeWebsiteUrl(value)?.let { Uri.parse(it) }
    }

    /**
     * Converts a social media field to a profile URL.
     * URL building logic delegated to [ContactPatterns].
     *
     * Note: For full social network support, use VauchiMobile.getProfileUrl() directly.
     */
    private fun socialToUri(label: String, value: String): Uri? {
        return try {
            ContactPatterns.buildSocialProfileUrl(label, value)?.let { Uri.parse(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Detects the type of value and converts to appropriate URI.
     * Used for Custom fields. Detection logic delegated to [ContactPatterns].
     */
    private fun detectAndConvert(value: String): Uri? {
        return when (ContactPatterns.detectValueType(value)) {
            ContactPatterns.DetectedType.URL -> Uri.parse(value)
            ContactPatterns.DetectedType.EMAIL -> Uri.parse("mailto:$value")
            ContactPatterns.DetectedType.PHONE -> Uri.parse("tel:$value")
            ContactPatterns.DetectedType.UNKNOWN -> null
        }
    }

    /**
     * Copies a value to the clipboard.
     */
    private fun copyToClipboard(context: Context, value: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    /**
     * Returns an icon for a field type.
     */
    fun getFieldIcon(fieldType: MobileFieldType): String {
        return when (fieldType) {
            MobileFieldType.PHONE -> "\uD83D\uDCDE"  // Phone
            MobileFieldType.EMAIL -> "\u2709\uFE0F"  // Envelope
            MobileFieldType.WEBSITE -> "\uD83C\uDF10" // Globe
            MobileFieldType.ADDRESS -> "\uD83D\uDCCD" // Pin
            MobileFieldType.SOCIAL -> "\uD83D\uDC64"  // Person
            MobileFieldType.CUSTOM -> "\uD83D\uDCCB"  // Clipboard
        }
    }

    /**
     * Returns a description of the action for a field type.
     */
    fun getActionDescription(fieldType: MobileFieldType): String {
        return when (fieldType) {
            MobileFieldType.PHONE -> "Tap to call"
            MobileFieldType.EMAIL -> "Tap to email"
            MobileFieldType.WEBSITE -> "Tap to open"
            MobileFieldType.ADDRESS -> "Tap for directions"
            MobileFieldType.SOCIAL -> "Tap to view profile"
            MobileFieldType.CUSTOM -> "Tap to copy"
        }
    }
}
