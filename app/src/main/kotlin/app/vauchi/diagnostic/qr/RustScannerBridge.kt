// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic.qr

import android.content.Context
import android.util.Log
import uniffi.vauchi_platform.MobileScannerBackend
import uniffi.vauchi_platform.diagnosticLoadYoloModel
import uniffi.vauchi_platform.diagnosticScanQr
import uniffi.vauchi_platform.diagnosticScanQrYolo

/**
 * Bridge to the Rust rqrr and YOLO+rqrr scanners via UniFFI.
 */
object RustScannerBridge {
    private const val TAG = "Vauchi"
    private const val MODEL_FILENAME = "qrdet-n.onnx"

    val isAvailable: Boolean = true
    private var yoloLoaded = false

    /**
     * Load the YOLO model from app assets. Call once before using YoloRqrr mode.
     * Extracts the ONNX model from assets to internal storage, then loads it.
     */
    fun loadYoloModel(context: Context): Boolean {
        if (yoloLoaded) return true
        return try {
            // Extract ONNX model from assets to internal files dir
            val modelFile = java.io.File(context.filesDir, MODEL_FILENAME)
            if (!modelFile.exists()) {
                context.assets.open(MODEL_FILENAME).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "[QR Tuner] YOLO model extracted to ${modelFile.absolutePath}")
            }
            yoloLoaded = diagnosticLoadYoloModel(modelFile.absolutePath)
            if (yoloLoaded) {
                Log.i(TAG, "[QR Tuner] YOLO model loaded successfully")
            } else {
                Log.e(TAG, "[QR Tuner] YOLO model load failed")
            }
            yoloLoaded
        } catch (e: Exception) {
            Log.e(TAG, "[QR Tuner] YOLO model load error: ${e.message}")
            false
        }
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
                    val result =
                        diagnosticScanQrYolo(
                            lumaData = lumaData,
                            width = width.toUInt(),
                            height = height.toUInt(),
                            confidenceThreshold = 0.3f,
                        )
                    result.decoded
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
