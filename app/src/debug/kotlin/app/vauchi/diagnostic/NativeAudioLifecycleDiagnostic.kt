// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.vauchi.proximity.AudioProximityService

internal class NativeAudioLifecycleDiagnostic(
    private val context: Context,
) {
    fun run(
        name: String,
        log: MutableList<String>,
    ) {
        val recording = name.contains("record")
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
}
