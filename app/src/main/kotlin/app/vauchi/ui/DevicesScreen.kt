// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.components.QRCountdownContent
import app.vauchi.util.ClipboardUtils
import app.vauchi.util.LocalizationManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.vauchi_platform.MobileDeviceInfo
import uniffi.vauchi_platform.MobileDeviceLinkData

/**
 * Screen for managing linked devices.
 * Based on: features/device_management.feature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel,
    getDevices: () -> List<MobileDeviceInfo>,
    generateLinkQr: () -> MobileDeviceLinkData,
    unlinkDevice: (UInt) -> Boolean,
    isPrimaryDevice: () -> Boolean,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
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
                title = { Text(localizationManager.t("devices.linked")) },
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
                },
            )
        },
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            errorMessage != null -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error: $errorMessage",
                            color = MaterialTheme.colorScheme.error,
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = "${localizationManager.t("devices.title")} (${devices.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text =
                                if (isPrimary) {
                                    "This is the primary device. You can link additional devices."
                                } else {
                                    "This device is linked to your primary identity."
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    items(devices) { device ->
                        DeviceCard(
                            device = device,
                            onUnlink =
                                if (!device.isCurrent) {
                                    {
                                        deviceToUnlink = device
                                        showUnlinkDialog = true
                                    }
                                } else {
                                    null
                                },
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Multi-Device Sync",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text =
                                        "Link multiple devices to access your contacts from anywhere. " +
                                            "All devices share the same identity and stay in sync.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            viewModel = viewModel,
            onDismiss = {
                viewModel.cancelDeviceLink()
                showLinkDialog = false
            },
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
                        "The device will no longer have access to your identity.",
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
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Unlink")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnlinkDialog = false
                    deviceToUnlink = null
                }) {
                    Text(localizationManager.t("action.cancel"))
                }
            },
        )
    }
}

/**
 * Full device linking dialog with state-driven flow.
 *
 * Phases: QR display -> waiting for request -> confirmation (name + code)
 *         -> proximity verification -> completing -> success/failed
 *
 * Uses relay transport for the device link protocol. When bindings are not yet
 * available, falls back to the QR-only flow with a "waiting" indicator.
 */
@Composable
fun DeviceLinkDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    val deviceLinkState by viewModel.deviceLinkState.collectAsState()
    val proximitySupported by viewModel.proximitySupported.collectAsState()
    val proximityCapability by viewModel.proximityCapability.collectAsState()

    // null = not selected, "internet" = relay flow, "offline" = QR-only stub
    var selectedTransport by remember { mutableStateOf<String?>(null) }

    // Start the protocol only after internet transport is selected
    LaunchedEffect(selectedTransport) {
        if (selectedTransport == "internet") {
            viewModel.startDeviceLinkInitiator()
        }
    }

    // When we reach WaitingForRequest, start listening for relay request
    LaunchedEffect(deviceLinkState) {
        if (deviceLinkState is MainViewModel.DeviceLinkState.WaitingForRequest) {
            viewModel.listenForDeviceLinkRequest()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    selectedTransport == null -> {
                        "Link New Device"
                    }

                    selectedTransport == "offline" -> {
                        "Offline Device Linking"
                    }

                    else -> {
                        when (deviceLinkState) {
                            is MainViewModel.DeviceLinkState.Idle,
                            is MainViewModel.DeviceLinkState.GeneratingQR,
                            -> "Link New Device"

                            is MainViewModel.DeviceLinkState.WaitingForRequest -> "Link New Device"

                            is MainViewModel.DeviceLinkState.ConfirmingDevice -> "Confirm Device"

                            is MainViewModel.DeviceLinkState.VerifyingProximity -> "Verify Proximity"

                            is MainViewModel.DeviceLinkState.Completing -> "Completing Link"

                            is MainViewModel.DeviceLinkState.Success -> "Device Linked"

                            is MainViewModel.DeviceLinkState.Failed -> "Link Failed"

                            is MainViewModel.DeviceLinkState.Expired -> "QR Expired"
                        }
                    }
                },
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (selectedTransport) {
                    null -> {
                        // Transport selection
                        Text(
                            text = "How would you like to link?",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Choose how to connect with your new device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { selectedTransport = "internet" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Link via Internet")
                        }
                        OutlinedButton(
                            onClick = { selectedTransport = "offline" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.QrCode,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Link Offline (QR)")
                        }
                    }

                    "offline" -> {
                        // Offline stub
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = "Offline linking",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Coming soon \u2014 offline device linking requires protocol updates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "This mode will use animated QR codes to exchange device linking data without requiring an internet connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { selectedTransport = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Back")
                        }
                    }

                    "internet" -> {
                        // Existing relay flow
                        when (val state = deviceLinkState) {
                            is MainViewModel.DeviceLinkState.Idle,
                            is MainViewModel.DeviceLinkState.GeneratingQR,
                            -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Generating link...")
                            }

                            is MainViewModel.DeviceLinkState.WaitingForRequest -> {
                                QRCountdownContent(
                                    qrData = state.qrData,
                                    expiresAt = state.expiresAt,
                                    localizationManager = localizationManager,
                                    onCopy = {
                                        ClipboardUtils.copyWithAutoClear(
                                            context,
                                            coroutineScope,
                                            state.qrData,
                                            "Device Link",
                                        )
                                    },
                                    onExpired = {
                                        viewModel.setDeviceLinkExpired()
                                    },
                                )
                            }

                            is MainViewModel.DeviceLinkState.Expired -> {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = "QR code expired",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "QR Code Expired",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "The device link QR code has expired for security reasons. Generate a new one to continue.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = {
                                        viewModel.cancelDeviceLink()
                                        coroutineScope.launch {
                                            viewModel.startDeviceLinkInitiator()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Generate New QR")
                                }
                            }

                            is MainViewModel.DeviceLinkState.ConfirmingDevice -> {
                                // Device name
                                Text(
                                    text = "A device wants to link:",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                    ) {
                                        Text(
                                            text = state.deviceName,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Confirmation code:",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = state.confirmationCode,
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Text(
                                    text = "Verify this code matches what is shown on the other device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Proximity verification
                                ProximityVerificationSection(
                                    challenge = state.challenge,
                                    confirmationCode = state.confirmationCode,
                                    proximitySupported = proximitySupported,
                                    proximityCapability = proximityCapability,
                                    viewModel = viewModel,
                                    onVerified = { result ->
                                        coroutineScope.launch {
                                            viewModel.approveDeviceLink(result)
                                        }
                                    },
                                    onCancel = onDismiss,
                                )
                            }

                            is MainViewModel.DeviceLinkState.VerifyingProximity -> {
                                ProximityVerificationSection(
                                    challenge = state.challenge,
                                    confirmationCode = state.confirmationCode,
                                    proximitySupported = proximitySupported,
                                    proximityCapability = proximityCapability,
                                    viewModel = viewModel,
                                    onVerified = { result ->
                                        coroutineScope.launch {
                                            viewModel.approveDeviceLink(result)
                                        }
                                    },
                                    onCancel = onDismiss,
                                )
                            }

                            is MainViewModel.DeviceLinkState.Completing -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Completing device link...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            is MainViewModel.DeviceLinkState.Success -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Success",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Device linked successfully!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "The new device now shares your identity and will sync automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is MainViewModel.DeviceLinkState.Failed -> {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Failed",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = state.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(onClick = {
                                    coroutineScope.launch {
                                        viewModel.startDeviceLinkInitiator()
                                    }
                                }) {
                                    Text(localizationManager.t("action.retry"))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    when {
                        selectedTransport == null -> {
                            localizationManager.t("action.cancel")
                        }

                        selectedTransport == "offline" -> {
                            localizationManager.t("action.cancel")
                        }

                        else -> {
                            when (deviceLinkState) {
                                is MainViewModel.DeviceLinkState.Success -> localizationManager.t("action.done")
                                is MainViewModel.DeviceLinkState.Failed -> localizationManager.t("action.cancel")
                                else -> localizationManager.t("action.cancel")
                            }
                        }
                    },
                )
            }
        },
    )
}

/**
 * Proximity verification section used within the device link dialog.
 * Wraps the shared ProximityVerification composable with ViewModel callbacks.
 */
@Composable
private fun ProximityVerificationSection(
    challenge: ByteArray,
    confirmationCode: String,
    proximitySupported: Boolean,
    proximityCapability: String,
    viewModel: MainViewModel,
    onVerified: (app.vauchi.ui.components.ProximityVerificationResult) -> Unit,
    onCancel: () -> Unit,
) {
    app.vauchi.ui.components.ProximityVerification(
        challenge = challenge,
        confirmationCode = confirmationCode,
        proximitySupported = proximitySupported,
        proximityCapability = proximityCapability,
        onEmitChallenge = { ch -> viewModel.emitProximityChallenge(ch) },
        onListenForResponse = { timeout -> viewModel.listenForProximityResponse(timeout) },
        onStopVerification = { viewModel.stopProximityVerification() },
        onVerified = onVerified,
        onCancel = onCancel,
    )
}

@Composable
fun DeviceCard(
    device: MobileDeviceInfo,
    onUnlink: (() -> Unit)?,
) {
    val deviceIcon =
        when {
            device.deviceName.contains("iPhone", ignoreCase = true) -> Icons.Default.PhoneAndroid
            device.deviceName.contains("iPad", ignoreCase = true) -> Icons.Default.Tablet
            device.deviceName.contains("Mac", ignoreCase = true) -> Icons.Default.Laptop
            device.deviceName.contains("Watch", ignoreCase = true) -> Icons.Default.Watch
            device.deviceName.contains("Android", ignoreCase = true) -> Icons.Default.PhoneAndroid
            else -> Icons.Default.DesktopWindows
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${device.deviceName}, ${if (device.isCurrent) "current device, " else ""}${if (device.isActive) "active" else "inactive"}"
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                deviceIcon,
                contentDescription = null, // Described by parent card semantics
                modifier = Modifier.size(40.dp),
                tint =
                    if (device.isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
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
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.publicKeyPrefix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (device.isActive) "Active" else "Inactive",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (device.isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }

            // Unlink button for non-current devices
            if (onUnlink != null) {
                IconButton(onClick = onUnlink) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Unlink ${device.deviceName}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (device.isCurrent) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Current device",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Generate a QR code bitmap from string data
 */
private fun generateQRBitmap(
    data: String,
    size: Int,
): Bitmap? =
    try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix.get(x, y)) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    },
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
