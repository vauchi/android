// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.nfc

import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.vauchi.data.VauchiRepository
import com.vauchi.util.BiometricHelper
import uniffi.vauchi_platform.MobileNfcHandshake
import uniffi.vauchi_platform.MobileNfcState

/**
 * Minimal test activity for NFC encrypted exchange.
 *
 * Supports two modes:
 * - Reader (initiator): discovers a tag and drives the handshake
 * - HCE (responder): waits for a reader to connect via VauchiHceService
 *
 * Launch via: adb shell am start -n com.vauchi/.nfc.NfcTestActivity
 */
class NfcTestActivity : FragmentActivity() {
    companion object {
        private const val TAG = "NfcTest"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var repo: VauchiRepository? = null
    private var readerSession: MobileNfcHandshake? = null
    private val readerService = NfcReaderService()

    // Compose state
    private val _mode = mutableStateOf("idle") // idle, reader, hce
    private val _status = mutableStateOf("Authenticating...")
    private val _result = mutableStateOf<String?>(null)
    private val _nfcState = mutableStateOf("—")
    private val _authenticated = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                NfcTestScreen(
                    mode = _mode.value,
                    status = _status.value,
                    result = _result.value,
                    nfcState = _nfcState.value,
                    nfcAvailable = nfcAdapter != null,
                    onStartReader = { startReaderMode() },
                    onStartHce = { startHceMode() },
                    onReset = { reset() },
                )
            }
        }

        // Authenticate before allowing NFC operations
        BiometricHelper.authenticate(
            activity = this,
            title = "Unlock Vauchi",
            subtitle = "Authenticate to use NFC exchange",
            onSuccess = {
                try {
                    Log.d(TAG, "Biometric auth succeeded, creating repo...")
                    val r = VauchiRepository(this)
                    repo = r
                    // Trigger identity load from storage into memory
                    if (!r.hasIdentity()) {
                        _status.value = "No identity found — complete onboarding first"
                        return@authenticate
                    }
                    _authenticated.value = true
                    _status.value = "Tap a mode to begin"
                    Log.d(TAG, "Repo created successfully, identity loaded")
                } catch (e: Exception) {
                    Log.e(TAG, "Init failed", e)
                    _status.value = "Init failed: ${e.message ?: e.toString()}"
                }
            },
            onError = { msg ->
                Log.e(TAG, "Auth failed: $msg")
                _status.value = if (msg != null) "Auth failed: $msg" else "Auth cancelled"
            },
        )
    }

    override fun onPause() {
        super.onPause()
        disableNfcModes()
    }

    @Volatile
    private var exchangeInProgress = false

    private val readerCallback =
        NfcAdapter.ReaderCallback { tag ->
            // Prevent concurrent processing when reader mode re-discovers the tag
            if (exchangeInProgress) return@ReaderCallback
            exchangeInProgress = true

            Log.d(TAG, "ReaderMode tag discovered: ${tag.techList.joinToString()}")
            runOnUiThread { _status.value = "Tag found, exchanging..." }

            val session =
                readerSession ?: run {
                    exchangeInProgress = false
                    return@ReaderCallback
                }
            when (val outcome = readerService.performExchange(tag, session)) {
                is NfcExchangeOutcome.Success -> {
                    // Disable reader mode after success — no need to keep scanning
                    nfcAdapter?.disableReaderMode(this)
                    runOnUiThread {
                        _result.value = "Exchange complete!\n" +
                            "Remote: ${outcome.result.remoteDisplayName}\n" +
                            "Identity key: ${outcome.result.remoteIdentityKey.take(8).joinToString("") { "%02x".format(it) }}..."
                        _status.value = "Success"
                        _nfcState.value = session.state().toString()
                    }
                }

                is NfcExchangeOutcome.RelayFallback -> {
                    nfcAdapter?.disableReaderMode(this)
                    runOnUiThread {
                        _result.value = "Relay fallback triggered\n" +
                            "Exchange ID: ${outcome.exchangeId.take(8).joinToString("") { "%02x".format(it) }}..."
                        _status.value = "Relay fallback"
                        _nfcState.value = session.state().toString()
                    }
                }

                is NfcExchangeOutcome.Error -> {
                    // Create a fresh session for the next attempt
                    try {
                        readerSession = repo?.createNfcInitiator()
                        Log.d(TAG, "Created fresh session for retry")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create fresh session", e)
                    }
                    exchangeInProgress = false
                    runOnUiThread {
                        _result.value = "Error: ${outcome.message}"
                        _status.value = "Retrying..."
                        _nfcState.value = session.state().toString()
                    }
                }
            }
        }

    private fun startReaderMode() {
        val r =
            repo ?: run {
                _status.value = "Not authenticated"
                return
            }
        try {
            val session = r.createNfcInitiator()
            readerSession = session
            _mode.value = "reader"
            _status.value = "Hold phone near other device..."
            _nfcState.value = session.state().toString()
            enableReaderMode()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create reader session", e)
            _status.value = "Failed to create session: ${e.message ?: e.toString()}"
        }
    }

    private fun startHceMode() {
        val r =
            repo ?: run {
                _status.value = "Not authenticated"
                return
            }
        try {
            val session = r.createNfcResponder()
            VauchiHceService.activeSession = session
            _mode.value = "hce"
            _status.value = "HCE ready — hold other device's reader near this phone"
            _nfcState.value = session.state().toString()

            // Disable reader mode on HCE device to prevent it from
            // acting as a reader and interfering with the other device's reader
            nfcAdapter?.disableReaderMode(this)

            // Poll state changes
            Thread {
                while (_mode.value == "hce") {
                    val state = session.state()
                    _nfcState.value = state.toString()
                    if (state is MobileNfcState.Complete) {
                        _result.value = "Exchange complete!\n" +
                            "Remote: ${state.remoteDisplayName}"
                        _status.value = "Success"
                        break
                    }
                    if (state is MobileNfcState.Failed) {
                        _result.value = "Failed: ${state.error}"
                        _status.value = "Failed"
                        break
                    }
                    Thread.sleep(200)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HCE session", e)
            _status.value = "Failed to create session: ${e.message ?: e.toString()}"
        }
    }

    private fun reset() {
        _mode.value = "idle"
        _status.value = "Tap a mode to begin"
        _result.value = null
        _nfcState.value = "—"
        readerSession = null
        exchangeInProgress = false
        VauchiHceService.activeSession = null
        disableNfcModes()
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        // enableReaderMode forces the device into reader-only mode,
        // preventing peer-to-peer negotiation issues with HCE devices.
        // FLAG_READER_NFC_A covers IsoDep (ISO 14443-4) which HCE uses.
        // FLAG_READER_SKIP_NDEF_CHECK skips NDEF detection for faster connection.
        adapter.enableReaderMode(
            this,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        Log.d(TAG, "Reader mode enabled")
    }

    private fun disableNfcModes() {
        nfcAdapter?.disableReaderMode(this)
    }
}

@Composable
private fun NfcTestScreen(
    mode: String,
    status: String,
    result: String?,
    nfcState: String,
    nfcAvailable: Boolean,
    onStartReader: () -> Unit,
    onStartHce: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NFC Exchange Test", style = MaterialTheme.typography.headlineMedium)

        if (!nfcAvailable) {
            Text("NFC not available on this device", color = MaterialTheme.colorScheme.error)
        }

        Text("Mode: $mode", style = MaterialTheme.typography.bodyLarge)
        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
        Text("State: $nfcState", style = MaterialTheme.typography.bodySmall)

        if (result != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    result,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (mode == "idle") {
            Button(
                onClick = onStartReader,
                enabled = nfcAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start as Reader (Initiator)")
            }
            Button(
                onClick = onStartHce,
                enabled = nfcAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start as HCE (Responder)")
            }
        } else {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset")
            }
        }
    }
}
