// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.vauchi.ui.theme.VauchiTheme
import app.vauchi.util.generateQrBitmap
import kotlinx.coroutines.*
import uniffi.vauchi_platform.MobileQrEccLevel

private const val TAG = "Vauchi"

class QrDiagnosticActivity : ComponentActivity() {
    private val logLines = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private var cameraGranted by mutableStateOf(false)

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        setContent {
            VauchiTheme {
                QrDiagnosticAutoScreen(
                    logLines = logLines,
                    running = running,
                    onBack = { finish() },
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val testName = intent?.getStringExtra("test") ?: return
        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            runAutoTest(testName)
        }
    }

    private fun runAutoTest(test: String) {
        running = true
        logLines.clear()
        log("=== QR Diagnostic Auto-Test ===")
        log("Test: $test")

        when (test) {
            "generation", "all" -> {
                testQrGeneration()
                if (test == "all") {
                    testCameraPermission()
                }
            }

            "camera" -> {
                testCameraPermission()
            }

            else -> {
                log("Unknown test: $test")
            }
        }

        log("=== QR Diagnostic Complete ===")
        running = false
    }

    private fun testQrGeneration() {
        log("--- Test: QR Generation ---")
        val ecLevels = listOf("L", "M", "Q", "H")
        val ecMap =
            mapOf(
                "L" to MobileQrEccLevel.LOW,
                "M" to MobileQrEccLevel.MEDIUM,
                "Q" to MobileQrEccLevel.QUARTILE,
                "H" to MobileQrEccLevel.HIGH,
            )
        var passed = 0
        var failed = 0

        data class TestLevel(
            val name: String,
            val content: String,
        )

        val levels =
            listOf(
                TestLevel("Tiny 10ch", "HELLO12345"),
                TestLevel("Short 50ch", "INIT|" + "A".repeat(45)),
                TestLevel(
                    "INIT ~190ch",
                    "INIT|0123456789ABCDEFGHIJKLMN|" +
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLM|" +
                        "NOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ|" +
                        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ012345678901AB|" +
                        "Vauchi User",
                ),
                TestLevel(
                    "DATA ~700ch",
                    "DATA|0123456789ABCDEFGHIJKLMN|0/1|FF|A1B2|" +
                        (0 until 450).joinToString("") {
                            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[it % 36].toString()
                        },
                ),
            )

        for (level in levels) {
            var allOk = true
            for (ec in ecLevels) {
                try {
                    val bitmap =
                        generateQrBitmap(
                            data = level.content,
                            size = 512,
                            errorCorrection = ecMap[ec]!!,
                            margin = 2,
                        )
                    if (bitmap != null && bitmap.width > 0) {
                        passed++
                    } else {
                        failed++
                        allOk = false
                        log("FAIL: Null or zero-width bitmap for ${level.name} EC-$ec")
                    }
                } catch (e: Exception) {
                    failed++
                    allOk = false
                    log("FAIL: ${level.name} EC-$ec: ${e.message}")
                }
            }
            log("${if (allOk) "PASS" else "FAIL"}: ${level.name} (${level.content.length} chars) — all EC levels")
        }

        log("Generation summary: $passed passed, $failed failed out of ${passed + failed}")
    }

    private fun testCameraPermission() {
        log("--- Test: Camera Permission ---")
        if (cameraGranted) {
            log("PASS: Camera permission granted")
        } else {
            log("SKIP: Camera permission not granted (requesting...)")
            runOnUiThread { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, "[QR Diag] $msg")
        logLines.add(msg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrDiagnosticAutoScreen(
    logLines: List<String>,
    running: Boolean,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR Diagnostic") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }
            for (line in logLines) {
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when {
                            "FAIL" in line -> MaterialTheme.colorScheme.error
                            "PASS" in line -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }
}
