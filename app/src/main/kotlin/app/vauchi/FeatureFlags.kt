// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

/**
 * Feature flags for gating post-MVP features.
 *
 * Features gated here are fully implemented in vauchi-core but not yet
 * ready for user-facing release. Set to `true` when the feature is
 * ready for production.
 */
object FeatureFlags {
    /** Duress PIN: decoy data on coerced unlock */
    const val DURESS_PIN = false

    /** Visibility labels: group contacts and control field visibility */
    const val VISIBILITY_LABELS = false

    /** Tor Mode: route relay traffic through Tor */
    const val TOR_MODE = false

    /** Emergency Broadcast: encrypted alerts to trusted contacts */
    const val EMERGENCY_BROADCAST = false
}
