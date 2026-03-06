// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Saves diagnostic JPEG snapshots with JSON sidecar metadata during
 * camera tuner sweeps.
 */
class DiagnosticCapture(
    private val context: Context,
    private val sessionId: String,
) {
    companion object {
        private const val TAG = "QRTuner"
        private const val JPEG_QUALITY = 85
    }

    private val sessionDir: File by lazy {
        val dir =
            File(
                context.getExternalFilesDir("diagnostic"),
                "tuner/session_$sessionId/snapshots",
            )
        dir.mkdirs()
        dir
    }

    /**
     * Saves a JPEG snapshot with an accompanying JSON sidecar.
     *
     * @param imageBytes Raw JPEG or image bytes.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param frameIndex Zero-based frame index within the config run.
     * @param decodeResult Whether the QR code was successfully decoded.
     * @param configId Camera configuration ID.
     * @param qrConfigDescription Human-readable QR config description.
     * @param boundingBox Normalised bounding box [left, top, right, bottom] or null.
     * @param actualIso Actual ISO value applied (if known).
     * @param actualExposureEv Actual exposure EV applied (if known).
     * @param redactQr If true, draws a black rectangle over the QR bounding box region.
     */
    fun saveSnapshot(
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        frameIndex: Int,
        decodeResult: Boolean,
        configId: UInt,
        qrConfigDescription: String,
        boundingBox: RectF?,
        actualIso: Int?,
        actualExposureEv: Int?,
        redactQr: Boolean = false,
    ) {
        val baseName = "config_${configId}_frame_${String.format("%04d", frameIndex)}"
        val jpegFile = File(sessionDir, "$baseName.jpg")
        val jsonFile = File(sessionDir, "$baseName.json")

        try {
            val bitmap =
                if (redactQr && boundingBox != null) {
                    redactRegion(imageBytes, width, height, boundingBox)
                } else {
                    null
                }

            if (bitmap != null) {
                FileOutputStream(jpegFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                bitmap.recycle()
            } else {
                jpegFile.writeBytes(imageBytes)
            }

            val sidecar =
                JSONObject().apply {
                    put("timestamp_ms", System.currentTimeMillis())
                    put("config_id", configId.toLong())
                    put("qr_config", qrConfigDescription)
                    put("frame_index", frameIndex)
                    put("decode_result", decodeResult)
                    if (boundingBox != null) {
                        put(
                            "bounding_box",
                            JSONObject().apply {
                                put("left", boundingBox.left.toDouble())
                                put("top", boundingBox.top.toDouble())
                                put("right", boundingBox.right.toDouble())
                                put("bottom", boundingBox.bottom.toDouble())
                            },
                        )
                    }
                    actualIso?.let { put("actual_iso", it) }
                    actualExposureEv?.let { put("actual_exposure_ev", it) }
                    put("redacted", redactQr && boundingBox != null)
                }
            jsonFile.writeText(sidecar.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot $baseName", e)
        }
    }

    /**
     * Returns the session directory path for external tooling.
     */
    fun getSessionDir(): File = sessionDir

    private fun redactRegion(
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        normBox: RectF,
    ): Bitmap {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val mutable =
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(mutable)
        val paint =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

        val rect =
            RectF(
                normBox.left * mutable.width,
                normBox.top * mutable.height,
                normBox.right * mutable.width,
                normBox.bottom * mutable.height,
            )
        canvas.drawRect(rect, paint)

        return mutable
    }
}
