// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.Manifest
import android.os.Build

/**
 * Maps an exchange mode to the Android runtime permissions its ritual needs.
 *
 * Core owns *which capabilities* a mode requires
 * (`ExchangeMode::config().requires` in `core/vauchi-core/src/exchange/mode.rs`);
 * this is the platform-side mapping of those capabilities to Android
 * permissions, which is inherently frontend-specific — Android `CAMERA` vs
 * iOS `AVCaptureDevice`, version-gated Bluetooth permissions, etc. (ADR-030/031:
 * core decides the mode, the frontend executes the native bits, including the
 * permission request).
 *
 * Requested up front when a mode is selected (the "Permissions" step of the
 * Group → Mode → Permissions → Ritual flow) so the ritual itself stays a fast,
 * uninterrupted handshake. See
 * `_private/docs/problems/2026-06-06-exchange-ritual-flow/`.
 *
 * Omitted on purpose:
 * - **NFC** (`tap_tap`, partially `tap_hover_shake`): `android.permission.NFC`
 *   is install-time (auto-granted); a disabled NFC adapter is a system-settings
 *   concern, surfaced separately, not a runtime permission.
 * - **Accelerometer**: no runtime permission on Android.
 * - **USB / relay** (`cable`, `link`): no runtime permission.
 *
 * Keep the mode → capability set in sync with `ModeConfig.requires` in core.
 */
object ExchangeModePermissions {
    /**
     * Runtime permissions to request when [modeItemId] is selected. Accepts
     * either the core item id (`"mode:hover"`) or the bare serde name
     * (`"hover"`). Returns an empty list when no runtime permission is needed.
     *
     * [sdkInt] is injected (defaulting to the live build) so the version-gated
     * Bluetooth mapping is unit-testable.
     */
    // TODO(HUMBLE): T, P1. Maps exchange mode strings to Android permissions;
    // frontend transforms domain capability set into platform request. Fix:
    // core emits Command::RequestPermissions with capability list. (see
    // _private problem record 2026-07-06-mobile-domain-shell-violations)
    /**
     * Bluetooth runtime permissions for [sdkInt].
     *
     * Exposed on its own because the mode-selection step is not the only
     * place that needs them: core's BLE commands can reach the shell without
     * it having run, and an ungranted `BLUETOOTH_SCAN` makes `startScanning`
     * fail with no visible prompt — the device scans forever in silence.
     * `MainActivity` therefore requests these at execution time too, and both
     * call sites must ask for the same set.
     */
    fun bluetooth(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            // Android 12+: runtime Bluetooth permissions.
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            // Pre-Android 12: BLUETOOTH/BLUETOOTH_ADMIN are install-time;
            // BLE *scanning* requires location at runtime.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun forMode(
        modeItemId: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> {
        val camera = listOf(Manifest.permission.CAMERA)
        val mic = listOf(Manifest.permission.RECORD_AUDIO)
        val ble = bluetooth(sdkInt)

        return when (modeItemId.removePrefix("mode:")) {
            "hover" -> camera + mic

            // one-sided QR bootstrap + BLE card transfer
            "glance" -> camera + ble

            // one-sided/multi-stage QR scan
            "magic" -> ble + mic

            // BLE + ambient audio fingerprint
            "bump", "shake" -> ble

            // BLE + impact / accelerometer
            "tap_hover_shake" -> ble + mic

            // NFC + BLE + audio + accelerometer
            "tap_tap", "link", "cable" -> emptyList()

            // NFC install-time / relay / USB
            else -> emptyList()
        }.distinct()
    }
}
