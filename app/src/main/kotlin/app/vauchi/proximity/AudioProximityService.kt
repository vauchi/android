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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

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

    private val capture = AudioOperationSlot()
    private val playback = AudioOperationSlot()
    private val recordExecutor =
        // Replacement must enter the owner while the previous read is in flight.
        Executors.newFixedThreadPool(2) { r ->
            Thread(r, "AudioProximityRecord").apply { isDaemon = true }
        }
    private val mainHandler = Handler(Looper.getMainLooper())

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

        val hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

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
        val sampleRateInt = sampleRate.toInt()
        val bufferSize =
            AudioTrack.getMinBufferSize(
                sampleRateInt,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )

        if (bufferSize <= 0) return "Invalid buffer size for audio playback"
        var track: AudioTrack? = null
        var originalVolume: Int? = null
        return try {
            playback.run(
                start = {
                    originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                    val created =
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
                    track = created
                    val pcm = samples.toFloatArray()
                    check(created.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) == pcm.size) {
                        "Incomplete audio playback buffer"
                    }
                    created.play()
                },
                stop = {
                    try {
                        if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track?.stop()
                    } finally {
                        try {
                            track?.release()
                        } finally {
                            originalVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                        }
                    }
                },
            ) { operation ->
                val durationMs = (samples.size.toLong() * 1000) / sampleRateInt
                operation.await(durationMs + 100)
            }
            "" // Success
        } catch (e: Exception) {
            "Emit failed: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRateInt: Int): AudioRecord? {
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

        return record
    }

    /**
     * Record audio for [timeoutMs] and report (samples, recordedRate) via callback.
     *
     * [sampleRate] is core's suggested rate. If [AudioRecord] initializes at that
     * rate the recording matches; otherwise the actual operating rate from
     * [AudioRecord.getSampleRate] is reported so core can resample (Phase 1
     * resampler in audio_modem). Recording runs on a dedicated background
     * executor; the callback fires on the main thread.
     */
    fun receiveSignal(
        timeoutMs: ULong,
        sampleRate: UInt,
        completion: (List<Float>, UInt) -> Unit,
    ) {
        recordExecutor.execute {
            val (samples, recordedRate) = recordSamples(timeoutMs, sampleRate)
            mainHandler.post { completion(samples, recordedRate) }
        }
    }

    /**
     * Synchronous variant for the debug `ExistingCodeDiagnostic` loopback tool,
     * which already runs on a background thread. Production code
     * (`ExchangeCommandHandler`) uses the callback-based [receiveSignal] above.
     */
    fun receiveSignalSync(
        timeoutMs: ULong,
        sampleRate: UInt,
    ): List<Float> = recordSamples(timeoutMs, sampleRate).first

    private fun recordSamples(
        timeoutMs: ULong,
        sampleRate: UInt,
    ): Pair<List<Float>, UInt> {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return Pair(emptyList(), 0u)
        }

        var record: AudioRecord? = null
        return try {
            capture.run(
                start = {
                    val created = checkNotNull(createAudioRecord(sampleRate.toInt())) { "Audio recording unavailable" }
                    record = created
                    created.startRecording()
                },
                stop = {
                    try {
                        if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record?.stop()
                    } finally {
                        record?.release()
                    }
                },
            ) { operation ->
                val ownedRecord = checkNotNull(record)
                val recordedRate = ownedRecord.sampleRate.toUInt()
                val samples = mutableListOf<Float>()
                val buffer = FloatArray(ownedRecord.bufferSizeInFrames)
                val startTime = System.nanoTime()
                val timeoutMsLong = timeoutMs.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
                while (operation.isActive && (System.nanoTime() - startTime) / 1_000_000 < timeoutMsLong) {
                    val read =
                        try {
                            ownedRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        } catch (error: IllegalStateException) {
                            if (operation.isActive) throw error
                            break
                        }
                    if (read < 0) {
                        check(!operation.isActive) { "AudioRecord read failed: $read" }
                        break
                    }
                    for (i in 0 until read) samples.add(buffer[i])
                }
                Pair(samples, recordedRate)
            } ?: Pair(emptyList(), 0u)
        } catch (e: Exception) {
            Pair(emptyList(), 0u)
        }
    }

    /**
     * Check if audio is currently active.
     */
    fun isActive(): Boolean = capture.isActive || playback.isActive

    /**
     * Stop any ongoing audio operation.
     */
    fun stop() {
        try {
            playback.stop()
        } finally {
            capture.stop()
        }
    }

    private fun getOptimalSampleRate(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return sampleRateStr?.toIntOrNull() ?: 44100
    }
}
