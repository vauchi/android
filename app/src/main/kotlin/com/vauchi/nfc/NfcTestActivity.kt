// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.nfc

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vauchi.data.VauchiRepository
import uniffi.vauchi_mobile.MobileNfcHandshake
import uniffi.vauchi_mobile.MobileNfcState

/**
 * Minimal test activity for NFC encrypted exchange.
 *
 * Supports two modes:
 * - Reader (initiator): discovers a tag and drives the handshake
 * - HCE (responder): waits for a reader to connect via VauchiHceService
 *
 * Launch via: adb shell am start -n com.vauchi/.nfc.NfcTestActivity
 */
class NfcTestActivity : ComponentActivity() {
    companion object {
        private const val TAG = "NfcTest"
    }

    private var nfcAdapter: NfcAdapter? = null
    private var readerSession: MobileNfcHandshake? = null
    private val readerService = NfcReaderService()

    // Compose state
    private val _mode = mutableStateOf("idle") // idle, reader, hce
    private val _status = mutableStateOf("Tap a mode to begin")
    private val _result = mutableStateOf<String?>(null)
    private val _nfcState = mutableStateOf("—")

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
    }

    override fun onResume() {
        super.onResume()
        if (_mode.value == "reader") {
            enableForegroundDispatch()
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (_mode.value != "reader") return

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        Log.d(TAG, "Tag discovered: ${tag.techList.joinToString()}")
        _status.value = "Tag found, exchanging..."

        Thread {
            val session = readerSession ?: return@Thread
            when (val outcome = readerService.performExchange(tag, session)) {
                is NfcExchangeOutcome.Success -> {
                    _result.value = "Exchange complete!\n" +
                        "Remote: ${outcome.result.remoteDisplayName}\n" +
                        "Identity key: ${outcome.result.remoteIdentityKey.take(8).joinToString("") { "%02x".format(it) }}..."
                    _status.value = "Success"
                    _nfcState.value = session.state().toString()
                }

                is NfcExchangeOutcome.RelayFallback -> {
                    _result.value = "Relay fallback triggered\n" +
                        "Exchange ID: ${outcome.exchangeId.take(8).joinToString("") { "%02x".format(it) }}..."
                    _status.value = "Relay fallback"
                    _nfcState.value = session.state().toString()
                }

                is NfcExchangeOutcome.Error -> {
                    _result.value = "Error: ${outcome.message}"
                    _status.value = "Failed"
                    _nfcState.value = session.state().toString()
                }
            }
        }.start()
    }

    private fun startReaderMode() {
        val repo = VauchiRepository(this)
        try {
            val session = repo.createNfcInitiator()
            readerSession = session
            _mode.value = "reader"
            _status.value = "Hold phone near other device..."
            _nfcState.value = session.state().toString()
            enableForegroundDispatch()
        } catch (e: Exception) {
            _status.value = "Failed to create session: ${e.message}"
        }
    }

    private fun startHceMode() {
        val repo = VauchiRepository(this)
        try {
            val session = repo.createNfcResponder()
            VauchiHceService.activeSession = session
            _mode.value = "hce"
            _status.value = "HCE ready — hold other device's reader near this phone"
            _nfcState.value = session.state().toString()

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
            _status.value = "Failed to create session: ${e.message}"
        }
    }

    private fun reset() {
        _mode.value = "idle"
        _status.value = "Tap a mode to begin"
        _result.value = null
        _nfcState.value = "—"
        readerSession = null
        VauchiHceService.activeSession = null
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val intent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE,
            )
        val filters =
            arrayOf(
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            )
        val techLists = arrayOf(arrayOf("android.nfc.tech.IsoDep"))
        adapter.enableForegroundDispatch(this, intent, filters, techLists)
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
