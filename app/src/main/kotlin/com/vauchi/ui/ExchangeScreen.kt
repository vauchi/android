// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.vauchi.util.LocalizationManager
import uniffi.vauchi_mobile.MobileExchangeData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeScreen(
    onBack: () -> Unit,
    onGenerateQr: suspend () -> MobileExchangeData?,
    onScanQr: () -> Unit,
    proximitySupported: Boolean = false
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var exchangeData by remember { mutableStateOf<MobileExchangeData?>(null) }
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Text(localizationManager.t("sync.syncing"))
            } else if (hasError) {
                // Error state
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = localizationManager.t("exchange.qr_error"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Please check your internet connection and try again",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    modifier = Modifier.semantics { heading() }
                )

                qrBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .size(280.dp)
                            .semantics {
                                contentDescription = "Your contact exchange QR code. Show this to someone to let them scan and add you as a contact."
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null, // Handled by parent Card semantics
                                modifier = Modifier.size(260.dp)
                            )
                        }
                    }
                }

                exchangeData?.let { data ->
                    Text(
                        text = localizationManager.t("exchange.expires_in", mapOf("time" to formatExpiry(data.expiresAt))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Proximity verification status
                if (proximitySupported) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                android.R.drawable.ic_lock_silent_mode_off
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Ultrasonic verification ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onScanQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Scan QR code. Opens the camera to scan someone else's QR code and add them as a contact."
                        }
                ) {
                    Text(localizationManager.t("exchange.scan"))
                }
            }
        }
    }
}

@Composable
fun ScanQrDialog(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit
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
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    label = { Text("QR Data (wb://...)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onScan(manualInput) },
                enabled = manualInput.isNotBlank()
            ) {
                Text(localizationManager.t("contacts.add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizationManager.t("action.cancel"))
            }
        }
    )
}

private fun generateQrBitmap(data: String): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
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

private fun formatExpiry(timestamp: ULong): String {
    val seconds = timestamp.toLong()
    val now = System.currentTimeMillis() / 1000
    val diff = seconds - now
    return when {
        diff < 60 -> "Less than a minute"
        diff < 3600 -> "${diff / 60} minutes"
        else -> "${diff / 3600} hours"
    }
}
