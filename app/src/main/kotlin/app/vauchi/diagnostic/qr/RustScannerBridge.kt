// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.util.Log

/**
 * Bridge to the Rust rqrr scanner via UniFFI.
 *
 * Uses reflection to call `diagnosticScanQr` so the code compiles even
 * when the `diagnostic-scanner` feature is not enabled in the Rust build
 * (i.e., the UniFFI bindings don't include `MobileScannerBackend`).
 *
 * When the scanner bindings are available, calls go through normally.
 * When not available, returns null (scanner unavailable).
 */
object RustScannerBridge {
    private const val TAG = "Vauchi"

    /** Whether the scanner bindings are available at runtime. */
    val isAvailable: Boolean by lazy {
        try {
            Class.forName("uniffi.vauchi_platform.MobileScannerBackend")
            true
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "[QR Tuner] Rust scanner bindings not available (diagnostic-scanner feature not enabled)")
            false
        }
    }

    /**
     * Scan a QR code from Y-plane luma data using the Rust rqrr backend.
     *
     * @return decoded string, or null if decode failed or bindings unavailable.
     */
    fun scan(
        mode: ScannerMode,
        lumaData: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        if (!isAvailable) return null

        return try {
            // Call via reflection to avoid compile-time dependency on
            // the scanner bindings which are behind a feature flag.
            val backendClass = Class.forName("uniffi.vauchi_platform.MobileScannerBackend")
            val backend =
                if (mode == ScannerMode.RqrrRaw) {
                    backendClass.getField("RQRR_RAW").get(null)
                } else {
                    backendClass.getField("RQRR_PREPROCESSED").get(null)
                }

            val scanFn =
                Class
                    .forName("uniffi.vauchi_platform.Vauchi_platformKt")
                    .getMethod(
                        "diagnosticScanQr",
                        backendClass,
                        List::class.java,
                        UInt::class.java,
                        UInt::class.java,
                    )
            val lumaList = lumaData.map { it.toUByte() }
            val result = scanFn.invoke(null, backend, lumaList, width.toUInt(), height.toUInt())

            // Extract decoded field from MobileScanResult
            result?.javaClass?.getMethod("getDecoded")?.invoke(result) as? String
        } catch (e: Exception) {
            Log.e(TAG, "[QR Tuner] Rust scanner call failed: ${e.message}")
            null
        }
    }
}
