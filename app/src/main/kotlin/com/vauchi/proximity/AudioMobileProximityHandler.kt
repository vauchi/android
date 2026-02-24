// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.proximity

import uniffi.vauchi_mobile.MobileProximityHandler
import uniffi.vauchi_mobile.MobileProximityVerifier

/**
 * Adapts [AudioProximityService] to the [MobileProximityHandler] callback
 * interface expected by core's proximity exchange session.
 *
 * Core calls [verifyProximity] during the exchange flow with the protocol-level
 * challenge. This adapter emits the challenge as ultrasonic audio and listens
 * for the other device's response.
 */
class AudioMobileProximityHandler(
    private val verifier: MobileProximityVerifier,
) : MobileProximityHandler {
    override fun verifyProximity(
        challenge: ByteArray,
        timeoutMs: ULong,
    ): String {
        // Emit the challenge as ultrasonic audio
        val emitResult = verifier.emitChallenge(challenge)
        if (!emitResult.success) {
            return emitResult.error
        }

        // Listen for the other device's response
        val response = verifier.listenForResponse(timeoutMs)
        if (response.isEmpty()) {
            return "No proximity response received"
        }

        // Verify response matches challenge
        if (!response.contentEquals(challenge)) {
            return "Proximity verification failed: response mismatch"
        }

        return "" // Success
    }
}
