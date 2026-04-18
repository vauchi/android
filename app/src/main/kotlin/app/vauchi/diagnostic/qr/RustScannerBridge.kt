// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.content.Context
import android.util.Log
import uniffi.vauchi_platform.MobileScannerBackend
import uniffi.vauchi_platform.diagnosticScanQr

/**
 * Bridge to the Rust rxing/rqrr scanner via UniFFI.
 *
 * YOLO detector is only available with diagnostic-yolo feature
 * (not included in production builds).
 */
object RustScannerBridge {
    private const val TAG = "Vauchi"

    val isAvailable: Boolean = true

    /**
     * Load the YOLO model. Returns false in production builds
     * (diagnostic-yolo feature not enabled).
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadYoloModel(context: Context): Boolean {
        // YOLO requires diagnostic-yolo feature — not available in production builds
        Log.i(TAG, "[QR Tuner] YOLO not available (diagnostic-yolo feature not enabled)")
        return false
    }

    /**
     * Scan a QR code from Y-plane luma data.
     *
     * @return decoded string, or null if decode failed.
     */
    fun scan(
        mode: ScannerMode,
        lumaData: ByteArray,
        width: Int,
        height: Int,
    ): String? =
        try {
            when (mode) {
                ScannerMode.YoloRqrr -> {
                    Log.w(TAG, "[QR Tuner] YOLO not available, falling back to RqrrPreprocessed")
                    diagnosticScanQr(
                        backend = MobileScannerBackend.RQRR_PREPROCESSED,
                        lumaData = lumaData,
                        width = width.toUInt(),
                        height = height.toUInt(),
                    ).decoded
                }

                else -> {
                    val backend =
                        if (mode == ScannerMode.RqrrRaw) {
                            MobileScannerBackend.RQRR_RAW
                        } else {
                            MobileScannerBackend.RQRR_PREPROCESSED
                        }
                    diagnosticScanQr(
                        backend = backend,
                        lumaData = lumaData,
                        width = width.toUInt(),
                        height = height.toUInt(),
                    ).decoded
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[QR Tuner] Rust scanner call failed: ${e.message}")
            null
        }
}
