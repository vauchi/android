// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.proximity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import uniffi.vauchi_mobile.PlatformAudioHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Ultrasonic audio proximity verification service for Android.
 * Implements PlatformAudioHandler callback interface for vauchi-mobile.
 * 
 * Uses AudioRecord for recording and AudioTrack for playback at 18-20 kHz.
 */
class AudioProximityService(private val context: Context) : PlatformAudioHandler {
    
    companion object {
        private const val ULTRASONIC_MIN_FREQ = 18000
        private const val ULTRASONIC_MAX_FREQ = 20000
        
        @Volatile
        private var instance: AudioProximityService? = null
        
        fun getInstance(context: Context): AudioProximityService {
            return instance ?: synchronized(this) {
                instance ?: AudioProximityService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    // MARK: - PlatformAudioHandler Implementation
    
    /**
     * Check device capability for ultrasonic audio.
     * Returns: "full", "emit_only", "receive_only", or "none"
     */
    override fun checkCapability(): String {
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
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
    override fun emitSignal(samples: List<Float>, sampleRate: UInt): String {
        if (samples.isEmpty()) {
            return "No samples to emit"
        }
        
        return try {
            val sampleRateInt = sampleRate.toInt()
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRateInt,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            
            if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
                return "Invalid buffer size for audio playback"
            }
            
            val track = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateInt)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, samples.size * 4))
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
        }
    }
    
    /**
     * Record audio and return samples.
     * Returns recorded samples, or empty list on timeout/error.
     */
    override fun receiveSignal(timeoutMs: ULong, sampleRate: UInt): List<Float> {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasPermission) {
            return emptyList()
        }
        
        return try {
            val sampleRateInt = sampleRate.toInt()
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRateInt,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                return emptyList()
            }
            
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateInt,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize * 2
            )
            
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return emptyList()
            }
            
            audioRecord = record
            isRecording.set(true)
            
            val samples = mutableListOf<Float>()
            val buffer = FloatArray(bufferSize / 4)
            
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
            record.release()
            audioRecord = null
            isRecording.set(false)
            
            samples
            
        } catch (e: Exception) {
            isRecording.set(false)
            audioRecord?.release()
            audioRecord = null
            emptyList()
        }
    }
    
    /**
     * Check if audio is currently active.
     */
    override fun isActive(): Boolean {
        return isRecording.get() || isPlaying.get()
    }
    
    /**
     * Stop any ongoing audio operation.
     */
    override fun stop() {
        isRecording.set(false)
        isPlaying.set(false)
        
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioRecord = null
        
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
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
