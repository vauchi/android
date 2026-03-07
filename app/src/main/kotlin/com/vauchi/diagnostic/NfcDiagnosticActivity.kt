// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NFC diagnostic activity for testing NFC transport capabilities.
 *
 * Tests: Discovery, AID Selection, APDU Latency, Max Payload, Throughput.
 * Peer-to-peer: one device runs HCE (NfcDiagnosticHceService), other as reader.
 *
 * Launch via ADB:
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es test discovery
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es test aid_select
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es test apdu_latency
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es test max_payload
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es test throughput
 *   adb shell am start -n com.vauchi/.diagnostic.NfcDiagnosticActivity --es mode hce_server
 */
class NfcDiagnosticActivity : ComponentActivity() {
    companion object {
        private const val TAG = "NfcDiag"
        private const val TRANSCEIVE_TIMEOUT_MS = 5000
    }

    private var running by mutableStateOf(false)
    private val logLines = mutableStateListOf<String>()
    private var nfcAdapter: NfcAdapter? = null
    private var isHceMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        Log.i("Vauchi", "[NFC Diagnostic] Activity created")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiagnosticScreen()
                }
            }
        }
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val testName = intent?.getStringExtra("test")
        val mode = intent?.getStringExtra("mode")

        if (mode == "hce_server") {
            CoroutineScope(Dispatchers.Default).launch {
                delay(500)
                startHceServer(mutableListOf())
            }
            return
        }

        testName ?: return
        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            when (testName) {
                "discovery" -> runTest { testDiscovery(it) }
                "aid_select" -> runTest { testAidSelection(it) }
                "apdu_latency" -> runTest { testApduLatency(it) }
                "max_payload" -> runTest { testMaxPayload(it) }
                "throughput" -> runTest { testThroughput(it) }
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
                text = "NFC Diagnostic",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (nfcAdapter == null) {
                Text(
                    "NFC not available on this device",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { runTest { testDiscovery(it) } },
                    enabled = !running && nfcAdapter != null,
                ) { Text("A: Discovery") }

                Button(
                    onClick = { runTest { testAidSelection(it) } },
                    enabled = !running && nfcAdapter != null,
                ) { Text("B: AID Select") }

                Button(
                    onClick = { runTest { testApduLatency(it) } },
                    enabled = !running && nfcAdapter != null,
                ) { Text("C: APDU Latency") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(
                    onClick = { runTest { testMaxPayload(it) } },
                    enabled = !running && nfcAdapter != null,
                ) { Text("D: Max Payload") }

                Button(
                    onClick = { runTest { testThroughput(it) } },
                    enabled = !running && nfcAdapter != null,
                ) { Text("E: Throughput") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            ) {
                Button(
                    onClick = { runTest { startHceServer(it) } },
                    enabled = !running && nfcAdapter != null && !isHceMode,
                ) { Text("Start HCE Server") }

                Button(
                    onClick = {
                        isHceMode = false
                        logLines.add("HCE server stopped")
                    },
                    enabled = isHceMode,
                ) { Text("Stop HCE") }
            }

            if (running) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }

            Text(
                text = logLines.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
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
                Log.e("Vauchi", "[NFC Diagnostic] Test error: ${e.javaClass.simpleName}")
            } finally {
                for (line in lines) {
                    Log.i("Vauchi", "[NFC Diag] $line")
                }
                logLines.addAll(lines)
                running = false
            }
        }
    }

    // ── Test A: Discovery ────────────────────────────────────────────

    private suspend fun testDiscovery(log: MutableList<String>) {
        log.add("=== Test A: NFC Discovery ===")
        log.add("Enable reader mode, hold near HCE device...")

        var connectedIsoDep: IsoDep? = null
        var discoveryError: String? = null
        val startTime = System.currentTimeMillis()

        enableReaderMode { tag ->
            try {
                val iso = IsoDep.get(tag)
                if (iso != null) {
                    iso.timeout = TRANSCEIVE_TIMEOUT_MS
                    iso.connect()
                    connectedIsoDep = iso
                } else {
                    discoveryError = "No IsoDep (tech: ${tag.techList.joinToString()})"
                }
            } catch (e: Exception) {
                discoveryError = "Connect failed: ${e.javaClass.simpleName} ${e.message}"
            }
        }

        val deadline = System.currentTimeMillis() + 10_000
        while (connectedIsoDep == null && discoveryError == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        val elapsed = System.currentTimeMillis() - startTime

        val isoDep = connectedIsoDep
        if (isoDep != null) {
            val techList = isoDep.tag.techList.joinToString(", ") { it.substringAfterLast('.') }
            log.add("Found + connected in ${elapsed}ms")
            log.add("  Tech: $techList")
            log.add("  MaxTransceiveLength: ${isoDep.maxTransceiveLength}")
            log.add("  ExtendedAPDU: ${isoDep.isExtendedLengthApduSupported}")
            log.add("  Timeout: ${isoDep.timeout}ms")
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
            log.add("PASS: NFC peer discovered")
            DiagnosticLogger.logResult(
                "nfc_discovery",
                "pass",
                message = "elapsed=${elapsed}ms maxTransceive=${isoDep.maxTransceiveLength} extended=${isoDep.isExtendedLengthApduSupported}",
            )
        } else if (discoveryError != null) {
            log.add("FAIL: $discoveryError")
            DiagnosticLogger.logResult("nfc_discovery", "fail", message = discoveryError!!)
        } else {
            log.add("FAIL: No NFC tag found within 10s")
            DiagnosticLogger.logResult("nfc_discovery", "fail", message = "timeout")
        }
        nfcAdapter?.disableReaderMode(this@NfcDiagnosticActivity)
    }

    // ── Test B: AID Selection ────────────────────────────────────────

    private suspend fun testAidSelection(log: MutableList<String>) {
        log.add("=== Test B: AID Selection ===")
        log.add("Hold near HCE device...")

        withIsoDep(log, "nfc_aid_select") { isoDep ->
            val selectApdu = buildSelectAid(NfcDiagnosticHceService.DIAGNOSTIC_AID)

            val iterations = 5
            val times = mutableListOf<Long>()

            for (i in 1..iterations) {
                val start = System.nanoTime()
                val response = isoDep.transceive(selectApdu)
                val elapsed = (System.nanoTime() - start) / 1_000_000

                val ok = isSuccess(response)
                times.add(elapsed)
                log.add("  SELECT #$i: ${elapsed}ms ${if (ok) "OK" else "FAIL"}")

                if (!ok) {
                    log.add("FAIL: AID selection rejected")
                    DiagnosticLogger.logResult("nfc_aid_select", "fail", message = "rejected at iteration $i")
                    return@withIsoDep
                }
            }

            val mean = times.average()
            val pass = mean < 50
            log.add("Mean SELECT latency: ${"%.1f".format(mean)}ms ${if (pass) "PASS" else "FAIL"}")
            DiagnosticLogger.logResult(
                "nfc_aid_select",
                if (pass) "pass" else "fail",
                message = "mean=${mean}ms min=${times.min()} max=${times.max()}",
            )
        }
    }

    // ── Test C: APDU Round-Trip Latency ──────────────────────────────

    private suspend fun testApduLatency(log: MutableList<String>) {
        log.add("=== Test C: APDU Latency ===")
        log.add("Hold near HCE device...")

        withIsoDep(log, "nfc_apdu_latency") { isoDep ->
            // SELECT diagnostic AID first
            val selectResp = isoDep.transceive(buildSelectAid(NfcDiagnosticHceService.DIAGNOSTIC_AID))
            if (!isSuccess(selectResp)) {
                log.add("FAIL: AID selection failed")
                return@withIsoDep
            }

            val payload = ByteArray(20) { 0x42 }
            val iterations = 10
            val rtts = mutableListOf<Long>()

            for (i in 1..iterations) {
                val apdu = buildApdu(NfcDiagnosticHceService.INS_ECHO, payload)
                val start = System.nanoTime()
                val response = isoDep.transceive(apdu)
                val elapsed = (System.nanoTime() - start) / 1_000_000

                if (isSuccess(response)) {
                    rtts.add(elapsed)
                    log.add("  RTT #$i: ${elapsed}ms (resp=${response.size - 2} bytes)")
                } else {
                    log.add("  RTT #$i: FAIL")
                }
            }

            if (rtts.isEmpty()) {
                log.add("FAIL: No successful APDUs")
                DiagnosticLogger.logResult("nfc_apdu_latency", "fail", message = "no_success")
                return@withIsoDep
            }

            val mean = rtts.average()
            val pass = mean < 100
            log.add("Mean RTT: ${"%.1f".format(mean)}ms (${rtts.size}/$iterations succeeded) ${if (pass) "PASS" else "FAIL"}")
            DiagnosticLogger.logResult(
                "nfc_apdu_latency",
                if (pass) "pass" else "fail",
                message = "mean=${"%.1f".format(mean)}ms min=${rtts.min()} max=${rtts.max()} success=${rtts.size}/$iterations",
            )
        }
    }

    // ── Test D: Max Payload ──────────────────────────────────────────

    private suspend fun testMaxPayload(log: MutableList<String>) {
        log.add("=== Test D: Max Payload ===")
        log.add("Hold near HCE device...")

        withIsoDep(log, "nfc_max_payload") { isoDep ->
            val selectResp = isoDep.transceive(buildSelectAid(NfcDiagnosticHceService.DIAGNOSTIC_AID))
            if (!isSuccess(selectResp)) {
                log.add("FAIL: AID selection failed")
                return@withIsoDep
            }

            val maxTransceive = isoDep.maxTransceiveLength
            log.add("MaxTransceiveLength: $maxTransceive bytes")
            log.add("ExtendedAPDU: ${isoDep.isExtendedLengthApduSupported}")

            // Test increasing payload sizes
            val sizes = intArrayOf(16, 64, 128, 200, 255, 512, 1024)
            var maxSuccess = 0

            for (size in sizes) {
                if (size + 7 > maxTransceive) {
                    log.add("  ${size}B: SKIP (exceeds maxTransceive)")
                    continue
                }

                val payload = ByteArray(size) { (it % 256).toByte() }
                val apdu = buildApdu(NfcDiagnosticHceService.INS_PAYLOAD_TEST, payload)

                try {
                    val start = System.nanoTime()
                    val response = isoDep.transceive(apdu)
                    val elapsed = (System.nanoTime() - start) / 1_000_000

                    if (isSuccess(response)) {
                        val respData = response.sliceArray(0 until response.size - 2)
                        val match = respData.contentEquals(payload)
                        log.add("  ${size}B: ${elapsed}ms echo=${if (match) "match" else "MISMATCH(got ${respData.size})"}")
                        if (match) maxSuccess = size
                    } else {
                        log.add("  ${size}B: REJECTED (sw=${response.takeLast(2).joinToString("") { "%02x".format(it) }})")
                    }
                } catch (e: Exception) {
                    log.add("  ${size}B: ERROR (${e.message})")
                    break
                }
            }

            val pass = maxSuccess >= 200
            log.add("Max successful payload: ${maxSuccess}B ${if (pass) "PASS" else "FAIL"}")
            DiagnosticLogger.logResult(
                "nfc_max_payload",
                if (pass) "pass" else "fail",
                message = "max=$maxSuccess maxTransceive=$maxTransceive extended=${isoDep.isExtendedLengthApduSupported}",
            )
        }
    }

    // ── Test E: Throughput ───────────────────────────────────────────

    private suspend fun testThroughput(log: MutableList<String>) {
        log.add("=== Test E: Throughput ===")
        log.add("Hold near HCE device...")

        withIsoDep(log, "nfc_throughput") { isoDep ->
            val selectResp = isoDep.transceive(buildSelectAid(NfcDiagnosticHceService.DIAGNOSTIC_AID))
            if (!isSuccess(selectResp)) {
                log.add("FAIL: AID selection failed")
                return@withIsoDep
            }

            // Send multiple APDUs in sequence, measure aggregate throughput
            val chunkSize = 200 // safe payload size for short APDU
            val totalSizes = intArrayOf(1024, 5120, 10240)

            for (totalSize in totalSizes) {
                val chunks = (totalSize + chunkSize - 1) / chunkSize
                var sent = 0
                var failed = 0
                val start = System.nanoTime()

                for (i in 0 until chunks) {
                    val remaining = totalSize - sent
                    val thisChunk = remaining.coerceAtMost(chunkSize)
                    val payload = ByteArray(thisChunk) { (it % 256).toByte() }
                    val apdu = buildApdu(NfcDiagnosticHceService.INS_PAYLOAD_TEST, payload)

                    try {
                        val response = isoDep.transceive(apdu)
                        if (isSuccess(response)) {
                            sent += thisChunk
                        } else {
                            failed++
                        }
                    } catch (e: Exception) {
                        failed++
                        break
                    }
                }

                val elapsed = (System.nanoTime() - start) / 1_000_000
                val kbPerSec = if (elapsed > 0) (sent.toDouble() / elapsed) else 0.0

                log.add("  ${totalSize / 1024}KB: ${elapsed}ms (${"%.1f".format(kbPerSec)} KB/s, $failed failed)")
                DiagnosticLogger.logResult(
                    "nfc_throughput",
                    if (failed == 0) "pass" else "fail",
                    message = "size=$totalSize elapsed=$elapsed kbps=${"%.1f".format(kbPerSec)} failed=$failed",
                )
            }

            log.add("=== Test E complete ===")
        }
    }

    // ── HCE Server Mode ──────────────────────────────────────────────

    private fun startHceServer(log: MutableList<String>) {
        log.add("=== NFC HCE Server Mode ===")
        log.add("Diagnostic HCE service is active")
        log.add("Ready for reader connections on AID: F0564155434849D1")
        log.add("Hold the reader device near this device...")
        NfcDiagnosticHceService.reset()
        isHceMode = true
        // HCE runs via Android NFC stack automatically — no manual start needed
        // The service is activated when a reader selects our AID
        logLines.addAll(log)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun enableReaderMode(onTag: (Tag) -> Unit) {
        val adapter = nfcAdapter ?: return
        val extras =
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
            }
        adapter.enableReaderMode(
            this,
            { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            extras,
        )
    }

    private suspend fun withIsoDep(
        log: MutableList<String>,
        testName: String,
        block: suspend (IsoDep) -> Unit,
    ) {
        var connectedIsoDep: IsoDep? = null
        var connectError: String? = null

        enableReaderMode { tag ->
            try {
                val iso = IsoDep.get(tag)
                if (iso != null) {
                    iso.timeout = TRANSCEIVE_TIMEOUT_MS
                    iso.connect()
                    connectedIsoDep = iso
                } else {
                    connectError = "No IsoDep support"
                }
            } catch (e: Exception) {
                connectError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        val deadline = System.currentTimeMillis() + 10_000
        while (connectedIsoDep == null && connectError == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        val isoDep = connectedIsoDep
        if (isoDep == null) {
            nfcAdapter?.disableReaderMode(this@NfcDiagnosticActivity)
            val msg = connectError ?: "No NFC tag found within 10s"
            log.add("FAIL: $msg")
            DiagnosticLogger.logResult(testName, "fail", message = msg)
            return
        }

        try {
            block(isoDep)
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
            nfcAdapter?.disableReaderMode(this@NfcDiagnosticActivity)
        }
    }

    private fun buildSelectAid(aid: ByteArray): ByteArray {
        val apdu = ByteArray(5 + aid.size)
        apdu[0] = 0x00
        apdu[1] = 0xA4.toByte()
        apdu[2] = 0x04
        apdu[3] = 0x00
        apdu[4] = aid.size.toByte()
        System.arraycopy(aid, 0, apdu, 5, aid.size)
        return apdu
    }

    private fun buildApdu(
        ins: Byte,
        data: ByteArray,
    ): ByteArray {
        if (data.size <= 255) {
            val apdu = ByteArray(5 + data.size)
            apdu[0] = 0x00
            apdu[1] = ins
            apdu[2] = 0x00
            apdu[3] = 0x00
            apdu[4] = data.size.toByte()
            System.arraycopy(data, 0, apdu, 5, data.size)
            return apdu
        } else {
            val apdu = ByteArray(7 + data.size)
            apdu[0] = 0x00
            apdu[1] = ins
            apdu[2] = 0x00
            apdu[3] = 0x00
            apdu[4] = 0x00
            apdu[5] = (data.size shr 8).toByte()
            apdu[6] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, apdu, 7, data.size)
            return apdu
        }
    }

    private fun isSuccess(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
}
