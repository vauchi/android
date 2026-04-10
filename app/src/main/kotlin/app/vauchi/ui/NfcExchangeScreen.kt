// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vauchi.nfc.NfcExchangeOutcome
import app.vauchi.nfc.NfcReaderService
import app.vauchi.nfc.VauchiHceService
import app.vauchi.util.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vauchi_platform.MobileNfcHandshake

private val NfcBackground = Color(0xFFF0F4FF)
private val NfcIconColor = Color(0xFF3B5BDB)
private val InstructionTextColor = Color(0xFF555555)

/**
 * Sealed state for the NFC exchange flow.
 */
private sealed class NfcScreenState {
    /** Waiting for NFC tap — reader mode active, HCE responder ready. */
    data object Waiting : NfcScreenState()

    /** NFC tap detected, exchange in progress. */
    data object Exchanging : NfcScreenState()

    /** Exchange succeeded. Shows remote display name. */
    data class Success(
        val remoteDisplayName: String,
    ) : NfcScreenState()

    /** Exchange failed or NFC not available/enabled. */
    data class Error(
        val message: String,
    ) : NfcScreenState()

    /** Relay fallback triggered — NFC tap dropped mid-exchange. */
    data object RelayFallback : NfcScreenState()
}

/**
 * NFC exchange screen. Drives the [MobileNfcHandshake] protocol over NFC.
 *
 * Reader mode: this device acts as initiator using [NfcReaderService].
 * Responder mode: handled automatically by [VauchiHceService] when the
 * peer acts as initiator — we set [VauchiHceService.activeSession] to a
 * responder session.
 *
 * On success, shows the remote contact's display name.
 * Contact saving requires a platform API not yet available — flagged as concern.
 *
 * @param viewModel The main view model (for repository access).
 * @param onBack Navigate back to the exchange mode picker.
 * @param onDone Navigate to contacts after success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcExchangeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfcAvailable = nfcAdapter != null
    val nfcEnabled = nfcAvailable && (nfcAdapter?.isEnabled == true)

    var screenState by remember {
        mutableStateOf<NfcScreenState>(
            when {
                !nfcAvailable -> NfcScreenState.Error("NFC is not available on this device")
                !nfcEnabled -> NfcScreenState.Error("NFC is disabled. Please enable it in Settings.")
                else -> NfcScreenState.Waiting
            },
        )
    }

    // Sessions for initiator and responder
    var initiatorSession by remember { mutableStateOf<MobileNfcHandshake?>(null) }
    val readerService = remember { NfcReaderService() }

    // Set up NFC sessions when screen is ready
    LaunchedEffect(nfcEnabled) {
        if (!nfcEnabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val initiator = viewModel.createNfcInitiator()
                val responder = viewModel.createNfcResponder()
                initiatorSession = initiator
                VauchiHceService.activeSession = responder
                Log.d("Vauchi", "NFC sessions created")
            } catch (e: Exception) {
                Log.e("Vauchi", "Failed to create NFC sessions: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    screenState = NfcScreenState.Error("Failed to initialise NFC session. Please retry.")
                }
            }
        }
    }

    // Reader mode callback — invoked on a background thread by NfcAdapter
    val readerCallback =
        remember {
            NfcAdapter.ReaderCallback { tag: Tag ->
                val session = initiatorSession ?: return@ReaderCallback
                if (screenState !is NfcScreenState.Waiting) return@ReaderCallback

                coroutineScope.launch(Dispatchers.Main) {
                    screenState = NfcScreenState.Exchanging
                }

                coroutineScope.launch(Dispatchers.IO) {
                    val outcome = readerService.performExchange(tag, session)
                    withContext(Dispatchers.Main) {
                        when (outcome) {
                            is NfcExchangeOutcome.Success -> {
                                val name = outcome.result.remoteDisplayName
                                Log.d("Vauchi", "NFC exchange complete")
                                screenState = NfcScreenState.Success(name)
                                // Refresh contacts list so the new contact appears
                                viewModel.refresh()
                            }

                            is NfcExchangeOutcome.RelayFallback -> {
                                Log.d("Vauchi", "NFC relay fallback triggered")
                                screenState = NfcScreenState.RelayFallback
                            }

                            is NfcExchangeOutcome.Error -> {
                                Log.e("Vauchi", "NFC exchange error: ${outcome.message.take(60)}")
                                // Reset sessions for retry
                                try {
                                    val newInitiator = viewModel.createNfcInitiator()
                                    val newResponder = viewModel.createNfcResponder()
                                    initiatorSession = newInitiator
                                    VauchiHceService.activeSession = newResponder
                                } catch (_: Exception) {
                                }
                                screenState = NfcScreenState.Error(outcome.message)
                            }
                        }
                    }
                }
            }
        }

    // Enable/disable reader mode with the screen lifecycle
    val activity = context as? android.app.Activity
    DisposableEffect(nfcEnabled, nfcAdapter) {
        if (nfcEnabled && nfcAdapter != null && activity != null) {
            nfcAdapter.enableReaderMode(
                activity,
                readerCallback,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
            Log.d("Vauchi", "NFC reader mode enabled")
        }
        onDispose {
            if (nfcAdapter != null && activity != null) {
                try {
                    nfcAdapter.disableReaderMode(activity)
                    Log.d("Vauchi", "NFC reader mode disabled")
                } catch (_: Exception) {
                }
            }
            VauchiHceService.activeSession = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("exchange.mode.nfc")) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("nfc_exchange.back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = localizationManager.t("action.back"),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(NfcBackground)
                    .testTag("nfc_exchange_screen"),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = screenState) {
                is NfcScreenState.Waiting -> {
                    NfcWaitingContent(localizationManager)
                }

                is NfcScreenState.Exchanging -> {
                    NfcExchangingContent(localizationManager)
                }

                is NfcScreenState.Success -> {
                    NfcSuccessContent(
                        remoteDisplayName = state.remoteDisplayName,
                        localizationManager = localizationManager,
                        onDone = onDone,
                    )
                }

                is NfcScreenState.Error -> {
                    NfcErrorContent(
                        message = state.message,
                        localizationManager = localizationManager,
                        onRetry = {
                            screenState = NfcScreenState.Waiting
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val newInitiator = viewModel.createNfcInitiator()
                                    val newResponder = viewModel.createNfcResponder()
                                    initiatorSession = newInitiator
                                    VauchiHceService.activeSession = newResponder
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        screenState =
                                            NfcScreenState.Error(
                                                e.message ?: "Failed to reset session",
                                            )
                                    }
                                }
                            }
                        },
                    )
                }

                is NfcScreenState.RelayFallback -> {
                    NfcRelayFallbackContent(
                        localizationManager = localizationManager,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun NfcWaitingContent(localizationManager: LocalizationManager) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Default.Nfc,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = NfcIconColor,
        )
        Text(
            text = "Hold phones together",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Touch the NFC areas of both phones to exchange contact cards.",
            style = MaterialTheme.typography.bodyMedium,
            color = InstructionTextColor,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            modifier =
                Modifier
                    .fillMaxWidth(0.6f)
                    .testTag("nfc_exchange.waiting_indicator"),
        )
    }
}

@Composable
private fun NfcExchangingContent(localizationManager: LocalizationManager) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        CircularProgressIndicator(
            modifier =
                Modifier
                    .size(64.dp)
                    .testTag("nfc_exchange.exchanging_indicator"),
            color = NfcIconColor,
        )
        Text(
            text = "Exchanging…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Keep phones together until complete.",
            style = MaterialTheme.typography.bodyMedium,
            color = InstructionTextColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NfcSuccessContent(
    remoteDisplayName: String,
    localizationManager: LocalizationManager,
    onDone: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = localizationManager.t("status.success"),
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Exchange complete!",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = localizationManager.t("exchange.contact_added", mapOf("name" to remoteDisplayName)),
            style = MaterialTheme.typography.bodyMedium,
            color = InstructionTextColor,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDone,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("nfc_exchange.done"),
        ) {
            Text(localizationManager.t("action.done"))
        }
    }
}

@Composable
private fun NfcErrorContent(
    message: String,
    localizationManager: LocalizationManager,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = localizationManager.t("status.error"),
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Exchange failed",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("nfc_exchange.retry"),
        ) {
            Text(localizationManager.t("action.retry"))
        }
    }
}

@Composable
private fun NfcRelayFallbackContent(
    localizationManager: LocalizationManager,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            Icons.Default.Nfc,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Relay fallback active",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "NFC tap dropped. The exchange will complete via the relay when both devices reconnect.",
            style = MaterialTheme.typography.bodyMedium,
            color = InstructionTextColor,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBack,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("nfc_exchange.back_from_relay"),
        ) {
            Text(localizationManager.t("action.back"))
        }
    }
}
