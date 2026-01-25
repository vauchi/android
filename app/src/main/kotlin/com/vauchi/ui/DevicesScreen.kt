// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vauchi.util.ClipboardUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.vauchi_mobile.MobileDeviceInfo
import uniffi.vauchi_mobile.MobileDeviceLinkData

/**
 * Screen for managing linked devices.
 * Based on: features/device_management.feature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    getDevices: () -> List<MobileDeviceInfo>,
    generateLinkQr: () -> MobileDeviceLinkData,
    unlinkDevice: (UInt) -> Boolean,
    isPrimaryDevice: () -> Boolean
) {
    var devices by remember { mutableStateOf<List<MobileDeviceInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showUnlinkDialog by remember { mutableStateOf(false) }
    var deviceToUnlink by remember { mutableStateOf<MobileDeviceInfo?>(null) }
    var isPrimary by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Load devices on first composition
    LaunchedEffect(Unit) {
        try {
            devices = getDevices()
            isPrimary = isPrimaryDevice()
            isLoading = false
        } catch (e: Exception) {
            errorMessage = e.message
            isLoading = false
        }
    }

    fun refreshDevices() {
        coroutineScope.launch {
            isLoading = true
            try {
                devices = getDevices()
                isPrimary = isPrimaryDevice()
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = e.message
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linked Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showLinkDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Link Device")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error: $errorMessage",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { refreshDevices() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Devices (${devices.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isPrimary) {
                                "This is the primary device. You can link additional devices."
                            } else {
                                "This device is linked to your primary identity."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(devices) { device ->
                        DeviceCard(
                            device = device,
                            onUnlink = if (!device.isCurrent) {
                                {
                                    deviceToUnlink = device
                                    showUnlinkDialog = true
                                }
                            } else null
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Multi-Device Sync",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Link multiple devices to access your contacts from anywhere. " +
                                            "All devices share the same identity and stay in sync.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Link new device dialog
    if (showLinkDialog) {
        DeviceLinkDialog(
            onDismiss = { showLinkDialog = false },
            generateLinkQr = generateLinkQr
        )
    }

    // Unlink confirmation dialog
    if (showUnlinkDialog && deviceToUnlink != null) {
        AlertDialog(
            onDismissRequest = {
                showUnlinkDialog = false
                deviceToUnlink = null
            },
            title = { Text("Unlink Device?") },
            text = {
                Text(
                    "This will remove \"${deviceToUnlink!!.deviceName}\" from your linked devices. " +
                    "The device will no longer have access to your identity."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val success = unlinkDevice(deviceToUnlink!!.deviceIndex)
                                if (success) {
                                    refreshDevices()
                                } else {
                                    errorMessage = "Failed to unlink device"
                                }
                            } catch (e: Exception) {
                                errorMessage = e.message
                            }
                            showUnlinkDialog = false
                            deviceToUnlink = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Unlink")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnlinkDialog = false
                    deviceToUnlink = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DeviceLinkDialog(
    onDismiss: () -> Unit,
    generateLinkQr: () -> MobileDeviceLinkData
) {
    var linkData by remember { mutableStateOf<MobileDeviceLinkData?>(null) }
    var isGenerating by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var timeRemaining by remember { mutableStateOf(0L) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Generate QR on first composition
    LaunchedEffect(Unit) {
        try {
            linkData = generateLinkQr()
            val now = System.currentTimeMillis() / 1000
            timeRemaining = maxOf(0L, linkData!!.expiresAt.toLong() - now)
            isGenerating = false
        } catch (e: Exception) {
            errorMessage = e.message
            isGenerating = false
        }
    }

    // Countdown timer
    LaunchedEffect(linkData) {
        while (timeRemaining > 0) {
            delay(1000)
            timeRemaining--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link New Device") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    isGenerating -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Generating link...")
                    }
                    errorMessage != null -> {
                        Text(
                            text = "Error: $errorMessage",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    linkData != null -> {
                        Text(
                            text = "Scan this QR code on your new device",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // QR Code
                        val qrBitmap = remember(linkData!!.qrData) {
                            generateQRBitmap(linkData!!.qrData, 250)
                        }
                        if (qrBitmap != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White
                                )
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Device Link QR Code",
                                    modifier = Modifier
                                        .size(250.dp)
                                        .padding(8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Expiry timer
                        if (timeRemaining > 0) {
                            val minutes = timeRemaining / 60
                            val seconds = timeRemaining % 60
                            Text(
                                text = "Expires in ${minutes}:${seconds.toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (timeRemaining < 60)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "QR code expired",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                coroutineScope.launch {
                                    isGenerating = true
                                    try {
                                        linkData = generateLinkQr()
                                        val now = System.currentTimeMillis() / 1000
                                        timeRemaining = maxOf(0L, linkData!!.expiresAt.toLong() - now)
                                        errorMessage = null
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    }
                                    isGenerating = false
                                }
                            }) {
                                Text("Generate New Code")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Copy button
                        TextButton(onClick = {
                            ClipboardUtils.copyWithAutoClear(
                                context,
                                coroutineScope,
                                linkData!!.qrData,
                                "Device Link"
                            )
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Link")
                        }

                        Text(
                            text = "Open Vauchi on your new device and select \"Join Existing Identity\" to scan this code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun DeviceCard(
    device: MobileDeviceInfo,
    onUnlink: (() -> Unit)?
) {
    val deviceIcon = when {
        device.deviceName.contains("iPhone", ignoreCase = true) -> Icons.Default.PhoneAndroid
        device.deviceName.contains("iPad", ignoreCase = true) -> Icons.Default.Tablet
        device.deviceName.contains("Mac", ignoreCase = true) -> Icons.Default.Laptop
        device.deviceName.contains("Watch", ignoreCase = true) -> Icons.Default.Watch
        device.deviceName.contains("Android", ignoreCase = true) -> Icons.Default.PhoneAndroid
        else -> Icons.Default.DesktopWindows
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                deviceIcon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (device.isCurrent)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (device.isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = { },
                            label = { Text("Current") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.publicKeyPrefix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (device.isActive) "Active" else "Inactive",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            // Unlink button for non-current devices
            if (onUnlink != null) {
                IconButton(onClick = onUnlink) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Unlink device",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else if (device.isCurrent) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Current device",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Generate a QR code bitmap from string data
 */
private fun generateQRBitmap(data: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
