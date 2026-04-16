// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Ultrasonic audio proximity verification service for Android.
 * Audio methods kept as inherent methods after PlatformAudioHandler removal (ADR-031).
 *
 * Uses AudioRecord for recording and AudioTrack for playback at 18-20 kHz.
 */
class AudioProximityService(
    private val context: Context,
) {
    companion object {
        private const val ULTRASONIC_MIN_FREQ = 18000
        private const val ULTRASONIC_MAX_FREQ = 20000

        @Volatile
        private var instance: AudioProximityService? = null

        fun getInstance(context: Context): AudioProximityService =
            instance ?: synchronized(this) {
                instance ?: AudioProximityService(context.applicationContext).also { instance = it }
            }
    }

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var cachedRecord: AudioRecord? = null
    private var cachedSampleRate: Int = 0

    // MARK: - Audio Methods (formerly PlatformAudioHandler)

    /**
     * Check device capability for ultrasonic audio.
     * Returns: "full", "emit_only", "receive_only", or "none"
     */
    fun checkCapability(): String {
        val hasRecordPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Check if device has microphone
        val hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        // Check if device has speaker
        val hasSpeaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty()

        // Check sample rate support (need at least 44100 Hz for ultrasonic)
        val sampleRate = getOptimalSampleRate()
        val nyquist = sampleRate / 2
        val supportsUltrasonic = nyquist >= ULTRASONIC_MAX_FREQ

        if (!supportsUltrasonic) {
            return "none"
        }

        val canRecord = hasMicrophone && hasRecordPermission
        val canPlay = hasSpeaker

        return when {
            canRecord && canPlay -> "full"
            canPlay -> "emit_only"
            canRecord -> "receive_only"
            else -> "none"
        }
    }

    /**
     * Emit ultrasonic signal with given samples.
     * Returns empty string on success, error message on failure.
     */
    fun emitSignal(
        samples: List<Float>,
        sampleRate: UInt,
    ): String {
        if (samples.isEmpty()) {
            return "No samples to emit"
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        return try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            val sampleRateInt = sampleRate.toInt()
            val bufferSize =
                AudioTrack.getMinBufferSize(
                    sampleRateInt,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )

            if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
                return "Invalid buffer size for audio playback"
            }

            val track =
                AudioTrack
                    .Builder()
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(sampleRateInt)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setBufferSizeInBytes(maxOf(bufferSize, samples.size * 4))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

            audioTrack = track
            isPlaying.set(true)

            // Convert List<Float> to FloatArray
            val floatArray = samples.toFloatArray()

            track.write(floatArray, 0, floatArray.size, AudioTrack.WRITE_BLOCKING)
            track.play()

            // Wait for playback to complete
            val durationMs = (samples.size.toLong() * 1000) / sampleRateInt
            Thread.sleep(durationMs + 100)

            track.stop()
            track.release()
            audioTrack = null
            isPlaying.set(false)

            "" // Success
        } catch (e: Exception) {
            isPlaying.set(false)
            audioTrack?.release()
            audioTrack = null
            "Emit failed: ${e.message}"
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getOrCreateAudioRecord(sampleRateInt: Int): AudioRecord? {
        cachedRecord?.let { record ->
            if (cachedSampleRate == sampleRateInt && record.state == AudioRecord.STATE_INITIALIZED) {
                return record
            }
            record.release()
        }

        val bufferSize =
            AudioRecord.getMinBufferSize(
                sampleRateInt,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return null
        }

        val record =
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRateInt,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize * 2,
            )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        cachedRecord = record
        cachedSampleRate = sampleRateInt
        return record
    }

    /**
     * Record audio and return samples.
     * Returns recorded samples, or empty list on timeout/error.
     */
    fun receiveSignal(
        timeoutMs: ULong,
        sampleRate: UInt,
    ): List<Float> {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return emptyList()
        }

        return try {
            val sampleRateInt = sampleRate.toInt()
            val record = getOrCreateAudioRecord(sampleRateInt) ?: return emptyList()

            audioRecord = record
            isRecording.set(true)

            val bufferSize = record.bufferSizeInFrames
            val samples = mutableListOf<Float>()
            val buffer = FloatArray(bufferSize)

            record.startRecording()

            val startTime = System.currentTimeMillis()
            val timeoutMsLong = timeoutMs.toLong()

            while (isRecording.get() && (System.currentTimeMillis() - startTime) < timeoutMsLong) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    for (i in 0 until read) {
                        samples.add(buffer[i])
                    }
                }
            }

            record.stop()
            // Do NOT release — keep cached for reuse
            isRecording.set(false)

            samples
        } catch (e: Exception) {
            isRecording.set(false)
            emptyList()
        }
    }

    /**
     * Check if audio is currently active.
     */
    fun isActive(): Boolean = isRecording.get() || isPlaying.get()

    /**
     * Stop any ongoing audio operation.
     */
    fun stop() {
        isRecording.set(false)
        isPlaying.set(false)

        cachedRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        cachedRecord = null
        audioRecord = null

        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        audioTrack = null
    }

    // MARK: - Helper Methods

    private fun getOptimalSampleRate(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return sampleRateStr?.toIntOrNull() ?: 44100
    }
}
