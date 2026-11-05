// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.vauchi.proximity.AudioProximityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class NativeAudioLifecycleDiagnostic(
    private val context: Context,
) {
    fun run(
        name: String,
        log: MutableList<String>,
    ) {
        val recording = name.contains("record") || name == "lifecycle_duplex"
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (recording && permission != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLogger.logError(name, "lifecycle", "microphone permission not granted")
            return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val service = AudioProximityService.getInstance(context)
        service.stop()
        DiagnosticLogger.logResult(name, "lifecycle", message = "START silence=true samples_saved=false")
        try {
            if (name == "lifecycle_duplex") {
                val message = duplexReport(service)
                log.add(message)
                DiagnosticLogger.logResult(name, "lifecycle", message = message)
                return
            }
            if (name.contains("callback")) {
                val message = callbackReport(name, service)
                log.add(message)
                DiagnosticLogger.logResult(name, "lifecycle", message = message)
                return
            }
            val probe =
                AudioLifecycleProbe(
                    operation = { duration ->
                        if (recording) {
                            val count = service.receiveSignalSync(duration.toULong(), 44_100u).size
                            AudioProbeOutcome(samples = count)
                        } else {
                            val samples = List((duration * 44_100 / 1_000).toInt()) { 0f }
                            AudioProbeOutcome(error = service.emitSignal(samples, 44_100u))
                        }
                    },
                    active = service::isActive,
                    stop = service::stop,
                )
            val report = probe.measure(replace = name.endsWith("replace"))
            log.add("$name $report")
            DiagnosticLogger.logResult(name, "lifecycle", message = report.toString())
        } finally {
            service.stop()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }

    private fun duplexReport(service: AudioProximityService): String {
        val captured = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val samples = AtomicInteger()
        val captureStillActive = AtomicBoolean(false)
        val error =
            java.util.concurrent.atomic
                .AtomicReference("")
        service.receiveSignal(3000uL, 44_100u) { pcm, _ ->
            samples.set(pcm.size)
            captured.countDown()
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!service.isActive() && System.nanoTime() < deadline) Thread.sleep(5)
        val initiallyActive = service.isActive()
        Thread.sleep(250)
        service.emitSignal(List(44_100) { 0f }, 44_100u) {
            error.set(it)
            captureStillActive.set(service.isActive())
            emitted.countDown()
        }
        val emitReturned = emitted.await(5, TimeUnit.SECONDS)
        val captureReturned = captured.await(5, TimeUnit.SECONDS)
        val observed =
            initiallyActive && emitReturned && captureReturned && captureStillActive.get() &&
                error.get().isEmpty() && samples.get() > 0
        return "duplex_status=${if (observed) "observed" else "inconclusive"} " +
            "initial_capture_active=$initiallyActive emit_returned=$emitReturned capture_returned=$captureReturned " +
            "capture_active_after_emit=${captureStillActive.get()} capture_samples=${samples.get()} error=${error.get()}"
    }

    private fun callbackReport(
        name: String,
        service: AudioProximityService,
    ): String {
        val cancelledCallback = AtomicBoolean(false)
        val replacementSamples = AtomicInteger(-1)
        val replacementReturned = CountDownLatch(1)
        val error =
            java.util.concurrent.atomic
                .AtomicReference("")
        val recording = name.contains("record")

        fun launch(
            duration: Long,
            completion: (Int) -> Unit,
        ) {
            if (recording) {
                service.receiveSignal(duration.toULong(), 44_100u) { samples, _ -> completion(samples.size) }
            } else {
                service.emitSignal(List((duration * 44_100 / 1000).toInt()) { 0f }, 44_100u) {
                    error.set(it)
                    completion(-1)
                }
            }
        }
        launch(2000) { cancelledCallback.set(true) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!service.isActive() && System.nanoTime() < deadline) Thread.sleep(5)
        val initiallyActive = service.isActive()
        Thread.sleep(250)
        val replace = name.endsWith("replace")
        val actionTime = System.nanoTime()
        if (replace) {
            launch(3000) {
                replacementSamples.set(it)
                replacementReturned.countDown()
            }
        } else {
            service.stop()
        }
        val replacementDelivered = if (replace) replacementReturned.await(5, TimeUnit.SECONDS) else false
        if (!replace) Thread.sleep(2200)
        val windowMs = (System.nanoTime() - actionTime) / 1_000_000
        val observed = initiallyActive && !cancelledCallback.get() && (!replace || replacementDelivered) && error.get().isEmpty()
        return "callback_status=${if (observed) "observed" else "inconclusive"} " +
            "initial_active=$initiallyActive cancelled_callback_delivered=${cancelledCallback.get()} " +
            "replacement_callback_delivered=$replacementDelivered replacement_samples=${replacementSamples.get()} " +
            "observation_window_ms=$windowMs error=${error.get()}"
    }
}
