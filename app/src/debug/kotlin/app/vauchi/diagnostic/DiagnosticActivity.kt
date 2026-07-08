// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import androidx.compose.ui.res.stringResource
import app.vauchi.R

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class DiagnosticActivity : ComponentActivity() {
    companion object {
        private const val SAMPLE_RATE = 44100
        private val TEST_FREQUENCIES = intArrayOf(18500, 19500, 20500, 21000)
        private const val SNR_THRESHOLD_DB = 15.0
        private const val NOISE_FLOOR_THRESHOLD_DB = -30.0
    }

    private var running by mutableStateOf(false)
    private val logLines = mutableStateListOf<String>()
    private var micPermissionGranted by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micPermissionGranted = granted
            if (!granted) {
                logLines.add("ERROR: Microphone permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticScreen()
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Run tests via ADB intent extras. Examples:
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test loopback
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test noise_floor
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test sweep
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test source_comparison
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test cross_device_emit
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test cross_device_listen
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test existing_loopback
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test existing_noise
     *   adb shell am start -n app.vauchi/.diagnostic.DiagnosticActivity --es test all
     */
    private fun handleIntent(intent: Intent?) {
        val testName = intent?.getStringExtra("test") ?: return
        // Wait briefly for permission grant to settle
        CoroutineScope(Dispatchers.Default).launch {
            Thread.sleep(500)
            micPermissionGranted = true // Already granted via adb shell pm grant
            when (testName) {
                "loopback" -> {
                    runTest { testLoopback(it) }
                }

                "noise_floor" -> {
                    runTest { testNoiseFloor(it) }
                }

                "sweep" -> {
                    runTest { testSweep(it) }
                }

                "source_comparison" -> {
                    runTest { testSourceComparison(it) }
                }

                "cross_device_emit" -> {
                    runTest { testCrossDeviceEmit(it) }
                }

                "cross_device_listen" -> {
                    runTest { testCrossDeviceListen(it) }
                }

                "existing_loopback" -> {
                    runTest { log ->
                        ExistingCodeDiagnostic(this@DiagnosticActivity).runLoopbackTest(log)
                    }
                }

                "existing_noise" -> {
                    runTest { log ->
                        ExistingCodeDiagnostic(this@DiagnosticActivity).runNoiseFloorTest(log)
                    }
                }

                "all" -> {
                    runTest { log ->
                        testLoopback(log)
                        testNoiseFloor(log)
                        testSweep(log)
                        testSourceComparison(log)
                        ExistingCodeDiagnostic(this@DiagnosticActivity).runLoopbackTest(log)
                        ExistingCodeDiagnostic(this@DiagnosticActivity).runNoiseFloorTest(log)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DiagnosticScreen() {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ultrasonic Diagnostic",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { runTest { testLoopback(it) } },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_a_loopback)) }

                Button(
                    onClick = { runTest { testNoiseFloor(it) } },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_b_noise)) }

                Button(
                    onClick = { runTest { testSweep(it) } },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_d_sweep)) }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(
                    onClick = { runTest { testSourceComparison(it) } },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_f_source_cmp)) }

                Button(
                    onClick = { runTest { testCrossDeviceListen(it) } },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_c_listen)) }

                Button(
                    onClick = { runTest { testCrossDeviceEmit(it) } },
                    enabled = !running,
                ) { Text(stringResource(R.string.debug_c_emit)) }
            }

            Text(
                text = "Existing Code Track:",
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
                Button(
                    onClick = {
                        runTest { log ->
                            ExistingCodeDiagnostic(this@DiagnosticActivity).runLoopbackTest(log)
                        }
                    },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_a_loopback_existing)) }

                Button(
                    onClick = {
                        runTest { log ->
                            ExistingCodeDiagnostic(this@DiagnosticActivity).runNoiseFloorTest(log)
                        }
                    },
                    enabled = !running && micPermissionGranted,
                ) { Text(stringResource(R.string.debug_b_noise_existing)) }
            }

            Text(
                text = "Other Diagnostics:",
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
                Button(
                    onClick = {
                        startActivity(
                            Intent(
                                this@DiagnosticActivity,
                                app.vauchi.diagnostic.qr.QrTunerActivity::class.java,
                            ),
                        )
                    },
                    enabled = !running,
                ) { Text(stringResource(R.string.debug_qr_camera_tuner)) }
            }

            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp),
                )
            }

            val scrollState = rememberScrollState()
            Text(
                text = logLines.joinToString("\n"),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 16.dp)
                        .verticalScroll(scrollState),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }

    private fun runTest(test: suspend (MutableList<String>) -> Unit) {
        running = true
        CoroutineScope(Dispatchers.Default).launch {
            val lines = mutableListOf<String>()
            try {
                test(lines)
            } catch (e: Exception) {
                lines.add("ERROR: ${e.message}")
                DiagnosticLogger.logError("unknown", "error", e.message ?: "unknown error")
            } finally {
                logLines.addAll(lines)
                running = false
            }
        }
    }

    private fun emitAndRecord(
        frequencyHz: Int,
        durationMs: Int,
        audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    ): FloatArray {
        val samples = SineWaveGenerator.generate(frequencyHz, durationMs, SAMPLE_RATE)
        return emitAndRecordRaw(samples, durationMs, audioSource)
    }

    @SuppressLint("MissingPermission")
    private fun emitAndRecordRaw(
        samples: FloatArray,
        durationMs: Int,
        audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    ): FloatArray {
        val recordSamples = (SAMPLE_RATE * durationMs) / 1000
        val recorded = FloatArray(recordSamples)

        val minRecBuf =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        val recorder =
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minRecBuf * 4,
            )

        val track = buildAudioTrack(samples)

        recorder.startRecording()

        val emitThread =
            Thread {
                track.play()
                // MODE_STATIC: play() is non-blocking, wait for playback to complete
                Thread.sleep(durationMs.toLong())
                track.stop()
                track.release()
            }
        emitThread.start()

        var offset = 0
        while (offset < recordSamples) {
            val read = recorder.read(recorded, offset, recordSamples - offset, AudioRecord.READ_BLOCKING)
            if (read <= 0) break
            offset += read
        }

        emitThread.join()
        recorder.stop()
        recorder.release()

        return recorded
    }

    private fun emitOnly(
        frequencyHz: Int,
        durationMs: Int,
    ) {
        val samples = SineWaveGenerator.generate(frequencyHz, durationMs, SAMPLE_RATE)
        val track = buildAudioTrack(samples)
        track.play()
        Thread.sleep(durationMs.toLong())
        track.stop()
        track.release()
    }

    @SuppressLint("MissingPermission")
    private fun recordOnly(
        durationMs: Int,
        audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    ): FloatArray {
        val recordSamples = (SAMPLE_RATE * durationMs) / 1000
        val recorded = FloatArray(recordSamples)

        val minRecBuf =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        val recorder =
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minRecBuf * 4,
            )

        recorder.startRecording()
        var offset = 0
        while (offset < recordSamples) {
            val read = recorder.read(recorded, offset, recordSamples - offset, AudioRecord.READ_BLOCKING)
            if (read <= 0) break
            offset += read
        }
        recorder.stop()
        recorder.release()

        return recorded
    }

    private fun buildAudioTrack(samples: FloatArray): AudioTrack {
        val bufferSize = samples.size * Float.SIZE_BYTES
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.setVolume(AudioTrack.getMaxVolume())
        return track
    }

    private fun testLoopback(log: MutableList<String>) {
        log.add("=== Test A: Loopback ===")
        for (freq in TEST_FREQUENCIES) {
            log.add("Testing $freq Hz...")
            val recorded = emitAndRecord(freq, 3000)
            val snr = FftAnalyzer.computeSnrDb(recorded, freq, SAMPLE_RATE)
            val pass = snr >= SNR_THRESHOLD_DB
            val result = if (pass) "PASS" else "FAIL"
            log.add("  $freq Hz: SNR=%.1f dB -> $result".format(snr))
            DiagnosticLogger.logResult(
                test = "loopback",
                track = "A",
                frequencyHz = freq,
                snrDb = snr,
                detected = pass,
                message = result,
            )
        }
    }

    private fun testNoiseFloor(log: MutableList<String>) {
        log.add("=== Test B: Noise Floor ===")
        log.add("Recording 5s of silence...")
        val recorded = recordOnly(5000)
        val bins = FftAnalyzer.analyzeBand(recorded, 16000, 22000, 100, SAMPLE_RATE)
        var allPass = true
        for (bin in bins) {
            if (bin.magnitudeDb >= NOISE_FLOOR_THRESHOLD_DB) {
                log.add("  FAIL: ${bin.frequencyHz} Hz at %.1f dBFS".format(bin.magnitudeDb))
                allPass = false
            }
        }
        val result = if (allPass) "PASS" else "FAIL"
        log.add("Noise floor: $result")
        DiagnosticLogger.logResult(
            test = "noise_floor",
            track = "B",
            detected = allPass,
            message = result,
        )
    }

    private fun testCrossDeviceEmit(log: MutableList<String>) {
        log.add("=== Test C: Emit ===")
        for (freq in TEST_FREQUENCIES) {
            log.add("Emitting $freq Hz for 3s...")
            emitOnly(freq, 3000)
            Thread.sleep(500)
        }
        log.add("Emit sequence complete")
        DiagnosticLogger.logResult(
            test = "cross_device_emit",
            track = "C",
            message = "emit_complete",
        )
    }

    private fun testCrossDeviceListen(log: MutableList<String>) {
        log.add("=== Test C: Listen ===")
        log.add("Recording 15s...")
        val recorded = recordOnly(15000)
        for (freq in TEST_FREQUENCIES) {
            val snr = FftAnalyzer.computeSnrDb(recorded, freq, SAMPLE_RATE)
            val detected = snr >= SNR_THRESHOLD_DB
            val result = if (detected) "DETECTED" else "NOT DETECTED"
            log.add("  $freq Hz: SNR=%.1f dB -> $result".format(snr))
            DiagnosticLogger.logResult(
                test = "cross_device_listen",
                track = "C",
                frequencyHz = freq,
                snrDb = snr,
                detected = detected,
                message = result,
            )
        }
    }

    private fun testSweep(log: MutableList<String>) {
        log.add("=== Test D: Frequency Sweep ===")
        val (sweepSamples, markers) =
            SineWaveGenerator.generateSweep(
                startHz = 16000,
                endHz = 22000,
                stepHz = 500,
                stepDurationMs = 200,
                sampleRate = SAMPLE_RATE,
            )
        val totalDurationMs = markers.size * 200
        log.add("Sweep: ${markers.size} steps, ${totalDurationMs}ms total")

        val recorded = emitAndRecordRaw(sweepSamples, totalDurationMs)
        val samplesPerStep = (SAMPLE_RATE * 200) / 1000

        for ((freq, sampleOffset) in markers) {
            val end = minOf(sampleOffset + samplesPerStep, recorded.size)
            if (sampleOffset >= recorded.size) break
            val segment = recorded.copyOfRange(sampleOffset, end)
            val snr = FftAnalyzer.computeSnrDb(segment, freq, SAMPLE_RATE)
            val pass = snr >= SNR_THRESHOLD_DB
            val result = if (pass) "PASS" else "FAIL"
            log.add("  $freq Hz: SNR=%.1f dB -> $result".format(snr))
            DiagnosticLogger.logResult(
                test = "sweep",
                track = "D",
                frequencyHz = freq,
                snrDb = snr,
                detected = pass,
                message = result,
            )
        }
    }

    private fun testSourceComparison(log: MutableList<String>) {
        log.add("=== Test F: AudioSource Comparison ===")
        val testFreq = 19000
        val sources =
            listOf(
                "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
                "MIC" to MediaRecorder.AudioSource.MIC,
                "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            )
        for ((name, source) in sources) {
            log.add("Testing source: $name")
            try {
                val recorded = emitAndRecord(testFreq, 3000, source)
                val snr = FftAnalyzer.computeSnrDb(recorded, testFreq, SAMPLE_RATE)
                log.add("  $name: SNR=%.1f dB".format(snr))
                DiagnosticLogger.logResult(
                    test = "source_comparison",
                    track = "F",
                    frequencyHz = testFreq,
                    snrDb = snr,
                    audioSource = name,
                )
            } catch (e: Exception) {
                log.add("  $name: ERROR - ${e.message}")
                DiagnosticLogger.logError(
                    test = "source_comparison",
                    track = "F",
                    error = "$name: ${e.message}",
                )
            }
        }
    }
}
