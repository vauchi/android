// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.vauchi.proximity.AudioProximityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal class AudioCaptureStartupDiagnostic(
    private val context: Context,
) {
    private data class Case(
        val name: String,
        val stopMs: Long,
        val frames: Int?,
        val mode: Int,
    )

    fun run(log: MutableList<String>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLogger.logError("capture_startup", "startup", "microphone permission not granted")
            return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        emit(
            log,
            "START samples_saved=false unprocessed_supported=${audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}",
        )
        val cases =
            listOf(
                Case("full_block", 250, null, AudioRecord.READ_BLOCKING),
                Case("small_block", 250, 256, AudioRecord.READ_BLOCKING),
                Case("nonblock", 250, null, AudioRecord.READ_NON_BLOCKING),
                Case("full_block", 500, null, AudioRecord.READ_BLOCKING),
                Case("small_block", 500, 256, AudioRecord.READ_BLOCKING),
                Case("nonblock", 500, null, AudioRecord.READ_NON_BLOCKING),
                Case("full_block", 1000, null, AudioRecord.READ_BLOCKING),
            )
        val service = AudioProximityService.getInstance(context)
        service.stop()
        try {
            repeat(3) { repetition ->
                for (index in cases.indices) {
                    if (!nativeCase(cases[(index + repetition * 2) % cases.size], repetition, log)) return
                }
                for (settleMs in listOf(125L, 250L, 500L, 1000L)) {
                    val probe =
                        AudioLifecycleProbe(
                            operation = { duration ->
                                AudioProbeOutcome(samples = service.receiveSignalSync(duration.toULong(), 44_100u).size)
                            },
                            active = service::isActive,
                            stop = service::stop,
                        )
                    emit(log, "service repetition=$repetition stop_ms=$settleMs ${probe.measure(replace = false, settleMs = settleMs)}")
                }
            }
            emit(log, "COMPLETE repetitions=3 native_cases=21 service_cases=12")
        } finally {
            service.stop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun nativeCase(
        case: Case,
        repetition: Int,
        log: MutableList<String>,
    ): Boolean {
        val minimumBytes = AudioRecord.getMinBufferSize(44_100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minimumBytes <= 0) {
            emit(log, "native status=inconclusive min_buffer_bytes=$minimumBytes")
            return true
        }
        val creationEntry = System.nanoTime()
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                44_100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minimumBytes * 2,
            )
        val creationReturned = System.nanoTime()
        val running = AtomicBoolean(true)
        val done = CountDownLatch(1)
        val readerError = AtomicReference("")
        var stopError = ""
        var stoppedAt = 0L
        var stopReturnedAt = 0L
        var startEnteredAt = 0L
        var startReturnedAt = 0L
        var meter: CaptureReadMeter? = null
        val capacity = record.bufferSizeInFrames
        val requested = case.frames ?: capacity
        val buffer = FloatArray(requested)
        var routeType: Int? = null
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            startEnteredAt = System.nanoTime()
            record.startRecording()
            startReturnedAt = System.nanoTime()
            routeType = record.routedDevice?.type
            val reads = CaptureReadMeter(startReturnedAt)
            meter = reads
            Thread({
                try {
                    while (running.get()) {
                        val count = record.read(buffer, 0, requested, case.mode)
                        val returnedAt = System.nanoTime()
                        val nonZero = if (count > 0) (0 until count).count { buffer[it] != 0f } else 0
                        reads.record(returnedAt, count, nonZero)
                        if (count < 0) break
                        if (case.mode == AudioRecord.READ_NON_BLOCKING && count == 0) LockSupport.parkNanos(1_000_000)
                    }
                } catch (error: Exception) {
                    readerError.set(error.toString())
                } finally {
                    done.countDown()
                }
            }, "capture-startup-probe").apply {
                isDaemon = true
                start()
            }
            done.await(case.stopMs, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            readerError.set(error.toString())
            done.countDown()
        } finally {
            running.set(false)
            stoppedAt = System.nanoTime()
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (error: Exception) {
                stopError = error.toString()
            } finally {
                record.release()
                stopReturnedAt = System.nanoTime()
            }
        }
        val returned = done.await(2, TimeUnit.SECONDS)
        emit(
            log,
            "native repetition=$repetition case=${case.name} stop_ms=${case.stopMs} " +
                "min_buffer_bytes=$minimumBytes capacity_frames=$capacity read_frames=$requested route_type=$routeType " +
                "constructor_ms=${(creationReturned - creationEntry) / 1_000_000.0} " +
                "start_call_ms=${(startReturnedAt - startEnteredAt) / 1_000_000.0} " +
                "stop_entry_ms=${(stoppedAt - startReturnedAt) / 1_000_000.0} " +
                "stop_return_ms=${(stopReturnedAt - stoppedAt) / 1_000_000.0} worker_returned=$returned " +
                "read_error=${readerError.get()} stop_error=$stopError ${meter?.snapshot()}",
        )
        return returned
    }

    private fun emit(
        log: MutableList<String>,
        message: String,
    ) {
        log.add(message)
        DiagnosticLogger.logResult("capture_startup", "startup", message = message)
    }
}
