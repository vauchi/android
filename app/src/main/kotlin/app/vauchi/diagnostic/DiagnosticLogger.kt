// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.time.Instant

object DiagnosticLogger {
    private const val TAG = "ULTRASONIC_DIAG"

    fun logResult(
        test: String,
        track: String,
        frequencyHz: Int? = null,
        snrDb: Double? = null,
        magnitudeDb: Double? = null,
        detected: Boolean? = null,
        audioSource: String? = null,
        message: String? = null,
    ) {
        val json =
            JSONObject().apply {
                put("test", test)
                put("track", track)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("ts", Instant.now().toString())
                frequencyHz?.let { put("freq_hz", it) }
                snrDb?.let { put("snr_db", "%.1f".format(it)) }
                magnitudeDb?.let { put("magnitude_db", "%.1f".format(it)) }
                detected?.let { put("detected", it) }
                audioSource?.let { put("audio_source", it) }
                message?.let { put("message", it) }
            }
        Log.i(TAG, json.toString())
    }

    fun logError(
        test: String,
        track: String,
        error: String,
    ) {
        val json =
            JSONObject().apply {
                put("test", test)
                put("track", track)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("ts", Instant.now().toString())
                put("error", error)
            }
        Log.e(TAG, json.toString())
    }
}
