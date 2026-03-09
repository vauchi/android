// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.diagnostic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.vauchi.ui.components.PermissionRationaleDialog
import app.vauchi.ui.components.rememberMultiplePermissionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BLE diagnostic activity for testing optimal BLE settings on physical devices.
 *
 * Tests: Discovery, MTU Negotiation, Throughput, Latency, RSSI Range, Connection Stability.
 * Peer-to-peer: one device runs as GATT server (peripheral), other as GATT client (central).
 *
 * Launch via ADB:
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test discovery
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test mtu
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test throughput
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test latency
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test rssi
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es test stability
 *   adb shell am start -n app.vauchi/.diagnostic.BleDiagnosticActivity --es mode server
 */
class BleDiagnosticActivity : ComponentActivity() {
    companion object {
        private val DIAGNOSTIC_SERVICE_UUID: UUID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef12345678a0")
        private val DIAGNOSTIC_CHAR_UUID: UUID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef12345678a1")
        private const val MIN_MTU = 120
        private const val DEFAULT_MTU = 185
        private const val TEST_TIMEOUT_MS = 5000L
        private const val STABILITY_DURATION_MS = 30000L
        private const val PING_INTERVAL_MS = 1000L
    }

    private var running by mutableStateOf(false)
    private val logLines = mutableStateListOf<String>()
    private var blePermissionGranted by mutableStateOf(false)

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var gattServer: BluetoothGattServer? = null
    private var isServerMode by mutableStateOf(false)

    private val blePermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        Log.i("Vauchi", "[BLE Diagnostic] Activity created")

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

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            gatt?.close()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val testName = intent?.getStringExtra("test")
        val mode = intent?.getStringExtra("mode")

        if (mode == "server") {
            CoroutineScope(Dispatchers.Default).launch {
                delay(500)
                blePermissionGranted = true
                startServer(mutableListOf())
            }
            return
        }

        testName ?: return
        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            blePermissionGranted = true
            when (testName) {
                "discovery" -> runTest { testDiscovery(it) }
                "mtu" -> runTest { testMtu(it) }
                "throughput" -> runTest { testThroughput(it) }
                "latency" -> runTest { testLatency(it) }
                "rssi" -> runTest { testRssi(it) }
                "stability" -> runTest { testStability(it) }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DiagnosticScreen() {
        val permState =
            rememberMultiplePermissionsState(
                permissions = blePermissions,
                title = "Bluetooth & Location Required",
                rationale =
                    "Vauchi needs Bluetooth to exchange contact cards with nearby devices. " +
                        "Location access is required by Android to scan for Bluetooth devices.",
            )
        LaunchedEffect(Unit) { permState.request() }
        LaunchedEffect(permState.allGranted) { blePermissionGranted = permState.allGranted }
        PermissionRationaleDialog(permState)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BLE Diagnostic",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { runTest { testDiscovery(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("A: Discovery") }

                Button(
                    onClick = { runTest { testMtu(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("B: MTU") }

                Button(
                    onClick = { runTest { testThroughput(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("C: Throughput") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Button(
                    onClick = { runTest { testLatency(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("D: Latency") }

                Button(
                    onClick = { runTest { testRssi(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("E: RSSI") }

                Button(
                    onClick = { runTest { testStability(it) } },
                    enabled = !running && blePermissionGranted,
                ) { Text("F: Stability") }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            ) {
                Button(
                    onClick = { runTest { startServer(it) } },
                    enabled = !running && blePermissionGranted && !isServerMode,
                ) { Text("Start Server") }

                Button(
                    onClick = {
                        stopServer()
                        isServerMode = false
                    },
                    enabled = isServerMode,
                ) { Text("Stop Server") }
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
                Log.e("Vauchi", "[BLE Diagnostic] Test error: ${e.javaClass.simpleName}")
            } finally {
                for (line in lines) {
                    Log.i("Vauchi", "[BLE Diag] $line")
                }
                logLines.addAll(lines)
                running = false
            }
        }
    }

    // ── Test A: Discovery ────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testDiscovery(log: MutableList<String>) {
        log.add("=== Test A: Discovery ===")
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log.add("FAIL: BLE scanner not available")
            return
        }

        val found = mutableListOf<ScanResult>()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    if (found.none { it.device.address == result.device.address }) {
                        found.add(result)
                    }
                }
            }

        log.add("Scanning for ${TEST_TIMEOUT_MS}ms...")
        scanner.startScan(callback)
        delay(TEST_TIMEOUT_MS)
        scanner.stopScan(callback)

        log.add("Found ${found.size} device(s):")
        for (result in found) {
            val name = result.device.name ?: "(unknown)"
            log.add("  $name  RSSI=${result.rssi} dBm  addr=${result.device.address}")
        }

        val hasPeer =
            found.any { result ->
                result.scanRecord?.serviceUuids?.any { it.uuid == DIAGNOSTIC_SERVICE_UUID } == true
            }
        if (hasPeer) {
            log.add("PASS: Vauchi BLE diagnostic peer found")
            DiagnosticLogger.logResult("ble_discovery", "pass", message = "peer_found=true count=${found.size}")
        } else {
            log.add("INFO: No Vauchi peer found (start server on other device)")
            DiagnosticLogger.logResult("ble_discovery", "info", message = "no_peer count=${found.size}")
        }
    }

    // ── Test B: MTU Negotiation ──────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testMtu(log: MutableList<String>) {
        log.add("=== Test B: MTU Negotiation ===")
        val device = findPeerDevice(log) ?: return

        var negotiatedMtu = 0
        var connected = false

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected = true
                        g.requestMtu(512)
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    negotiatedMtu = mtu
                }
            }

        gatt = device.connectGatt(this, false, callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (negotiatedMtu == 0 && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        gatt?.disconnect()

        if (negotiatedMtu >= MIN_MTU) {
            log.add("PASS: MTU=$negotiatedMtu (>= $MIN_MTU)")
            DiagnosticLogger.logResult("ble_mtu", "pass", message = "mtu=$negotiatedMtu")
        } else if (negotiatedMtu > 0) {
            log.add("FAIL: MTU=$negotiatedMtu (< $MIN_MTU)")
            DiagnosticLogger.logResult("ble_mtu", "fail", message = "mtu=$negotiatedMtu")
        } else {
            log.add("FAIL: MTU negotiation timed out (connected=$connected)")
            DiagnosticLogger.logResult("ble_mtu", "fail", message = "timeout connected=$connected")
        }
    }

    // ── Test C: Throughput ───────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testThroughput(log: MutableList<String>) {
        log.add("=== Test C: Throughput ===")
        val device = findPeerDevice(log) ?: return

        var connected = false
        var mtu = DEFAULT_MTU
        var characteristic: BluetoothGattCharacteristic? = null

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected = true
                        g.requestMtu(512)
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    m: Int,
                    status: Int,
                ) {
                    mtu = m
                    g.discoverServices()
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    characteristic =
                        g
                            .getService(DIAGNOSTIC_SERVICE_UUID)
                            ?.getCharacteristic(DIAGNOSTIC_CHAR_UUID)
                }
            }

        gatt = device.connectGatt(this, false, callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (characteristic == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        if (characteristic == null) {
            log.add("FAIL: Could not discover diagnostic characteristic")
            gatt?.disconnect()
            return
        }

        val sizes = intArrayOf(1024, 5120, 10240)
        for (size in sizes) {
            val data = ByteArray(size) { (it % 256).toByte() }
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            val startTime = System.currentTimeMillis()

            var offset = 0
            while (offset < data.size) {
                val end = (offset + chunkSize).coerceAtMost(data.size)
                characteristic!!.value = data.copyOfRange(offset, end)
                gatt?.writeCharacteristic(characteristic!!)
                offset = end
                delay(5) // BLE write pacing
            }

            val elapsed = System.currentTimeMillis() - startTime
            val kbPerSec = if (elapsed > 0) (size.toDouble() / elapsed) else 0.0
            val pass = kbPerSec >= 2.0
            log.add("  ${size / 1024}KB: ${elapsed}ms (${String.format("%.1f", kbPerSec)} KB/s) ${if (pass) "PASS" else "FAIL"}")
            DiagnosticLogger.logResult(
                "ble_throughput",
                if (pass) "pass" else "fail",
                message = "size=$size elapsed=$elapsed kbps=$kbPerSec",
            )
        }

        gatt?.disconnect()
    }

    // ── Test D: Latency ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testLatency(log: MutableList<String>) {
        log.add("=== Test D: Latency ===")
        val device = findPeerDevice(log) ?: return

        var characteristic: BluetoothGattCharacteristic? = null
        var writeComplete = false

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.requestMtu(512)
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    m: Int,
                    status: Int,
                ) {
                    g.discoverServices()
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    characteristic =
                        g
                            .getService(DIAGNOSTIC_SERVICE_UUID)
                            ?.getCharacteristic(DIAGNOSTIC_CHAR_UUID)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    writeComplete = true
                }
            }

        gatt = device.connectGatt(this, false, callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (characteristic == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        if (characteristic == null) {
            log.add("FAIL: Could not discover characteristic")
            gatt?.disconnect()
            return
        }

        val rtts = mutableListOf<Long>()
        val pingData = ByteArray(20) { 0x42 }

        for (i in 0 until 10) {
            writeComplete = false
            val start = System.currentTimeMillis()
            characteristic!!.value = pingData
            characteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt?.writeCharacteristic(characteristic!!)

            val writeDeadline = System.currentTimeMillis() + 2000
            while (!writeComplete && System.currentTimeMillis() < writeDeadline) {
                delay(1)
            }
            val rtt = System.currentTimeMillis() - start
            rtts.add(rtt)
            log.add("  Ping ${i + 1}: ${rtt}ms")
        }

        gatt?.disconnect()

        val mean = rtts.average()
        val pass = mean < 150
        log.add("Mean RTT: ${String.format("%.1f", mean)}ms ${if (pass) "PASS" else "FAIL"}")
        DiagnosticLogger.logResult("ble_latency", if (pass) "pass" else "fail", message = "mean_rtt=$mean")
    }

    // ── Test E: RSSI Range ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testRssi(log: MutableList<String>) {
        log.add("=== Test E: RSSI Range ===")
        val device = findPeerDevice(log) ?: return

        var connected = false
        val rssiValues = mutableListOf<Int>()

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected = true
                    }
                }

                override fun onReadRemoteRssi(
                    g: BluetoothGatt,
                    rssi: Int,
                    status: Int,
                ) {
                    rssiValues.add(rssi)
                }
            }

        gatt = device.connectGatt(this, false, callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (!connected && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        if (!connected) {
            log.add("FAIL: Could not connect")
            return
        }

        log.add("Reading RSSI every 500ms for 10s...")
        repeat(20) {
            gatt?.readRemoteRssi()
            delay(500)
        }

        gatt?.disconnect()

        if (rssiValues.isEmpty()) {
            log.add("FAIL: No RSSI readings")
            return
        }

        val avg = rssiValues.average()
        val min = rssiValues.min()
        val max = rssiValues.max()
        val allAboveThreshold = rssiValues.all { it > -80 }

        log.add("RSSI: avg=${String.format("%.0f", avg)} min=$min max=$max (${rssiValues.size} readings)")
        if (allAboveThreshold) {
            log.add("PASS: All readings > -80 dBm")
            DiagnosticLogger.logResult("ble_rssi", "pass", message = "avg=$avg min=$min max=$max")
        } else {
            log.add("FAIL: Some readings <= -80 dBm")
            DiagnosticLogger.logResult("ble_rssi", "fail", message = "avg=$avg min=$min max=$max")
        }
    }

    // ── Test F: Connection Stability ─────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun testStability(log: MutableList<String>) {
        log.add("=== Test F: Connection Stability ===")
        val device = findPeerDevice(log) ?: return

        var connected = false
        var disconnected = false
        var characteristic: BluetoothGattCharacteristic? = null
        var writeComplete = false

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connected = true
                            g.requestMtu(512)
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (connected) disconnected = true
                        }
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    m: Int,
                    status: Int,
                ) {
                    g.discoverServices()
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    characteristic =
                        g
                            .getService(DIAGNOSTIC_SERVICE_UUID)
                            ?.getCharacteristic(DIAGNOSTIC_CHAR_UUID)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    s: Int,
                ) {
                    writeComplete = true
                }
            }

        gatt = device.connectGatt(this, false, callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (characteristic == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }

        if (characteristic == null) {
            log.add("FAIL: Could not set up connection")
            gatt?.disconnect()
            return
        }

        log.add("Holding connection for ${STABILITY_DURATION_MS / 1000}s with pings...")
        var pings = 0
        var successes = 0
        val end = System.currentTimeMillis() + STABILITY_DURATION_MS

        while (System.currentTimeMillis() < end && !disconnected) {
            writeComplete = false
            pings++
            characteristic!!.value = byteArrayOf(pings.toByte())
            characteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt?.writeCharacteristic(characteristic!!)

            val pingDeadline = System.currentTimeMillis() + 2000
            while (!writeComplete && System.currentTimeMillis() < pingDeadline) {
                delay(1)
            }
            if (writeComplete) successes++
            delay(PING_INTERVAL_MS)
        }

        gatt?.disconnect()

        val drops = pings - successes
        log.add("Pings: $pings sent, $successes succeeded, $drops dropped")
        if (drops == 0 && !disconnected) {
            log.add("PASS: 0 drops, connection stable")
            DiagnosticLogger.logResult("ble_stability", "pass", message = "pings=$pings drops=0")
        } else {
            log.add("FAIL: $drops drops, disconnected=$disconnected")
            DiagnosticLogger.logResult("ble_stability", "fail", message = "pings=$pings drops=$drops disconnected=$disconnected")
        }
    }

    // ── GATT Server (peripheral mode) ────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startServer(log: MutableList<String>) {
        log.add("=== Starting GATT Server ===")

        val serverCallback =
            object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(
                    device: BluetoothDevice,
                    status: Int,
                    newState: Int,
                ) {
                    val state = if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"
                    Log.i("Vauchi", "[BLE Server] device $state addr=${device.address} status=$status")
                    logLines.add("Server: device $state")
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?,
                ) {
                    Log.i(
                        "Vauchi",
                        "[BLE Server] writeReq reqId=$requestId responseNeeded=$responseNeeded " +
                            "len=${value?.size ?: 0} from=${device.address}",
                    )
                    if (responseNeeded) {
                        val sent = gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        Log.i("Vauchi", "[BLE Server] sendResponse reqId=$requestId result=$sent")
                    }
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(20) { 0x00 })
                }
            }

        gattServer = bluetoothManager?.openGattServer(this, serverCallback)
        // Set adapter name for scan response — iOS uses this for peripheral identity
        bluetoothAdapter?.name = "Vauchi-Diag"
        val service = BluetoothGattService(DIAGNOSTIC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char =
            BluetoothGattCharacteristic(
                DIAGNOSTIC_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        service.addCharacteristic(char)
        gattServer?.addService(service)

        // Start advertising
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            log.add("FAIL: BLE advertiser not available")
            return
        }

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

        val data =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(DIAGNOSTIC_SERVICE_UUID))
                .build()

        // Scan response carries the device name — iOS CoreBluetooth uses this
        // to resolve the peripheral identity across Android 12+ RPA rotations
        val scanResponse =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(true)
                .build()

        advertiser.startAdvertising(
            settings,
            data,
            scanResponse,
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i("Vauchi", "[BLE Server] Advertising started (mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel})")
                    logLines.add("Server: advertising started")
                    isServerMode = true
                }

                override fun onStartFailure(errorCode: Int) {
                    logLines.add("Server: advertising failed (error=$errorCode)")
                    Log.e("Vauchi", "[BLE Diagnostic] Advertise failed: $errorCode")
                }
            },
        )

        log.add("GATT server started, advertising...")
    }

    @SuppressLint("MissingPermission")
    private fun stopServer() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        gattServer?.close()
        gattServer = null
        isServerMode = false
    }

    // ── Helpers ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun findPeerDevice(log: MutableList<String>): BluetoothDevice? {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log.add("FAIL: BLE scanner not available")
            return null
        }

        var foundDevice: BluetoothDevice? = null
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    if (result.scanRecord?.serviceUuids?.any { it.uuid == DIAGNOSTIC_SERVICE_UUID } == true) {
                        foundDevice = result.device
                    }
                }
            }

        log.add("Scanning for Vauchi BLE peer...")
        scanner.startScan(callback)
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (foundDevice == null && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        scanner.stopScan(callback)

        if (foundDevice == null) {
            log.add("FAIL: No Vauchi peer found (start server on other device)")
        } else {
            log.add("Found peer: ${foundDevice!!.name ?: foundDevice!!.address}")
        }
        return foundDevice
    }
}
