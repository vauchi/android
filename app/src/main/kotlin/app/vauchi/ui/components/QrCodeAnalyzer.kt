// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.components

import androidx.camera.core.ImageAnalysis

/**
 * QR code analyzer using rxing via UniFFI (Rust) instead of ML Kit.
 *
 * Extracts Y-plane directly from camera frame and calls rxing tryHarder
 * for QR decoding. No Google Play Services dependency.
 *
 * At 240p: ~9ms decode, 100% decode rate on V4-V10 QR codes.
 */
class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            // Extract Y-plane (luma) directly — no RGB conversion
            val yPlane = mediaImage.planes[0]
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = yPlane.rowStride
            val bytes =
                if (rowStride == width) {
                    val buf = yPlane.buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } else {
                    val buf = yPlane.buffer
                    val data = ByteArray(width * height)
                    for (row in 0 until height) {
                        buf.position(row * rowStride)
                        buf.get(data, row * width, width)
                    }
                    data
                }

            // rxing tryHarder via UniFFI (RqrrPreprocessed = rxing multi-decoder)
            val result =
                uniffi.vauchi_platform.scanQr(
                    backend = uniffi.vauchi_platform.MobileScannerBackend.RQRR_PREPROCESSED,
                    lumaData = bytes,
                    width = width.toUInt(),
                    height = height.toUInt(),
                )

            handOnDecodedCodes(result, onQrCodeDetected)
        } catch (e: Exception) {
            android.util.Log.e("QrAnalyzer", "scan error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }
}

/**
 * Every code in the frame, in the order found: facing the other phone the
 * camera sees the peer's code and a reflection of this phone's own, which
 * Core drops by session id (vauchi/private#450).
 */
internal fun handOnDecodedCodes(
    result: uniffi.vauchi_platform.MobileScanResult,
    onQrCodeDetected: (String) -> Unit,
) {
    result.decodedAll.forEach(onQrCodeDetected)
}
