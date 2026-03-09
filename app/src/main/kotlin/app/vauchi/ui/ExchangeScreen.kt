// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import app.vauchi.data.ExchangeData
import app.vauchi.ui.components.ProximityVerification
import app.vauchi.ui.components.ProximityVerificationResult
import app.vauchi.util.LocalizationManager
import kotlinx.coroutines.delay

// / Legacy exchange flow state — kept for screenshot/accessibility tests.
// / The main app flow now uses MultiStageExchangeScreen.
sealed class ExchangeFlowState {
    data object Idle : ExchangeFlowState()

    data class Scanned(
        val peerName: String,
    ) : ExchangeFlowState()

    data object Completing : ExchangeFlowState()

    data class Success(
        val contactName: String,
    ) : ExchangeFlowState()

    data class Failed(
        val error: String,
    ) : ExchangeFlowState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeScreen(
    onBack: () -> Unit,
    onGenerateQr: suspend () -> ExchangeData?,
    onScanQr: () -> Unit,
    proximitySupported: Boolean = false,
    proximityCapability: String = "none",
    exchangeState: ExchangeFlowState = ExchangeFlowState.Idle,
    onEmitChallenge: (ByteArray) -> Boolean = { false },
    onListenForResponse: (ULong) -> ByteArray? = { null },
    onStopVerification: () -> Unit = {},
    onProximityVerified: (ProximityVerificationResult) -> Unit = {},
    onCancelProximity: () -> Unit = {},
    onExchangeDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var exchangeData by remember { mutableStateOf<ExchangeData?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(retryTrigger) {
        isLoading = true
        hasError = false
        exchangeData = onGenerateQr()
        if (exchangeData != null) {
            exchangeData?.let { data ->
                qrBitmap = generateQrBitmap(data.qrData)
            }
        } else {
            hasError = true
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("exchange.title")) },
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
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Proximity verification gate: show proximity UI when exchange is pending
            when (exchangeState) {
                is ExchangeFlowState.Scanned -> {
                    Text(
                        text = "Found ${exchangeState.peerName}!",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "Verifying proximity...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }

                is ExchangeFlowState.Completing -> {
                    // Exchange is being completed after proximity verification
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Completing exchange...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }

                is ExchangeFlowState.Success -> {
                    // Exchange completed successfully
                    Spacer(modifier = Modifier.height(32.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Contact exchanged!",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "The new contact has been added to your contacts list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onExchangeDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(localizationManager.t("action.done"))
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                is ExchangeFlowState.Failed -> {
                    // Exchange failed
                    Spacer(modifier = Modifier.height(32.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Failed",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Exchange failed",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = exchangeState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onCancelProximity,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(localizationManager.t("action.retry"))
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                is ExchangeFlowState.Idle -> {
                    // Normal exchange screen: show QR and scan button
                    if (isLoading) {
                        CircularProgressIndicator()
                        Text(localizationManager.t("sync.syncing"))
                    } else if (hasError) {
                        // Error state
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Error",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = localizationManager.t("exchange.qr_error"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please check your internet connection and try again",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { retryTrigger++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localizationManager.t("action.retry"))
                        }
                    } else {
                        Text(
                            text = localizationManager.t("exchange.your_qr"),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { heading() },
                        )

                        qrBitmap?.let { bitmap ->
                            Card(
                                modifier =
                                    Modifier
                                        .size(280.dp)
                                        .semantics {
                                            contentDescription =
                                                "Your contact exchange QR code. Show this to someone to let them scan and add you as a contact."
                                        },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = null, // Handled by parent Card semantics
                                        modifier = Modifier.size(260.dp),
                                    )
                                }
                            }
                        }

                        // Countdown timer + refresh button directly under QR
                        exchangeData?.let { data ->
                            // Live countdown that ticks every second
                            var remainingSeconds by remember(data.expiresAt) {
                                mutableLongStateOf(
                                    (data.expiresAt.toLong() - System.currentTimeMillis() / 1000).coerceAtLeast(0),
                                )
                            }

                            LaunchedEffect(data.expiresAt) {
                                while (remainingSeconds > 0) {
                                    delay(1000)
                                    remainingSeconds =
                                        (data.expiresAt.toLong() - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                                }
                                // Auto-refresh when expired
                                retryTrigger++
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        if (remainingSeconds > 0) {
                                            val minutes = remainingSeconds / 60
                                            val seconds = remainingSeconds % 60
                                            localizationManager.t(
                                                "exchange.expires_in",
                                                mapOf("time" to String.format("%d:%02d", minutes, seconds)),
                                            )
                                        } else {
                                            "Expired"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (remainingSeconds <= 30) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )

                                TextButton(onClick = { retryTrigger++ }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Refresh QR code",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Refresh")
                                }
                            }
                        }

                        // Scan button
                        Button(
                            onClick = onScanQr,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription =
                                            "Scan QR code. Opens the camera to scan someone else's QR code and add them as a contact."
                                    },
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localizationManager.t("exchange.scan"))
                        }

                        // Proximity verification status
                        if (proximitySupported) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    painter =
                                        androidx.compose.ui.res.painterResource(
                                            android.R.drawable.ic_lock_silent_mode_off,
                                        ),
                                    contentDescription = "Ultrasonic verification",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Ultrasonic verification ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // BLE Exchange stub
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Bluetooth Exchange",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Icon(
                                    painter =
                                        androidx.compose.ui.res.painterResource(
                                            android.R.drawable.stat_sys_data_bluetooth,
                                        ),
                                    contentDescription = "Bluetooth",
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Coming soon",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Exchange contact cards via Bluetooth when both devices are nearby. Requires Bluetooth hardware.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanQrDialog(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var manualInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizationManager.t("exchange.scan")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Camera scanning coming soon. For now, paste the QR data:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    label = { Text("QR Data (wb://...)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onScan(manualInput) },
                enabled = manualInput.isNotBlank(),
            ) {
                Text(localizationManager.t("contacts.add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizationManager.t("action.cancel"))
            }
        },
    )
}

private fun generateQrBitmap(data: String): Bitmap {
    val writer = QRCodeWriter()
    val hints =
        mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2,
        )
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 1024, 1024, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bitmap
}
