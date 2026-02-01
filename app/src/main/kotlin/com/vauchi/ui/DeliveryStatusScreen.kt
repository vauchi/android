// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vauchi.util.LocalizationManager
import uniffi.vauchi_mobile.MobileDeliveryRecord
import uniffi.vauchi_mobile.MobileDeliveryStatus
import uniffi.vauchi_mobile.MobileDeliverySummary
import uniffi.vauchi_mobile.MobileRetryEntry
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryStatusScreen(
    deliveryRecords: List<MobileDeliveryRecord>,
    retryEntries: List<MobileRetryEntry>,
    failedCount: Int,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRetry: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Recent", "Failed", "Pending")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delivery Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(title)
                                if (index == 1 && failedCount > 0) {
                                    Badge {
                                        Text(failedCount.toString())
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                selectedTab == 0 -> RecentDeliveriesList(deliveryRecords)
                selectedTab == 1 -> FailedDeliveriesList(
                    records = deliveryRecords.filter { it.status == MobileDeliveryStatus.FAILED },
                    onRetry = onRetry
                )
                selectedTab == 2 -> PendingRetriesList(retryEntries)
            }
        }
    }
}

@Composable
fun RecentDeliveriesList(records: List<MobileDeliveryRecord>) {
    if (records.isEmpty()) {
        EmptyDeliveryContent(
            icon = Icons.Default.CheckCircle,
            title = "No Recent Deliveries",
            message = "Messages you send will appear here."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { record ->
                DeliveryRecordCard(record)
            }
        }
    }
}

@Composable
fun FailedDeliveriesList(
    records: List<MobileDeliveryRecord>,
    onRetry: (String) -> Unit
) {
    if (records.isEmpty()) {
        EmptyDeliveryContent(
            icon = Icons.Default.CheckCircle,
            title = "No Failed Deliveries",
            message = "All messages have been delivered successfully."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { record ->
                FailedDeliveryCard(record, onRetry)
            }
        }
    }
}

@Composable
fun PendingRetriesList(entries: List<MobileRetryEntry>) {
    if (entries.isEmpty()) {
        EmptyDeliveryContent(
            icon = Icons.Default.Schedule,
            title = "No Pending Retries",
            message = "No messages are waiting to be retried."
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries) { entry ->
                RetryEntryCard(entry)
            }
        }
    }
}

@Composable
fun DeliveryRecordCard(record: MobileDeliveryRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            DeliveryStatusIcon(record.status)

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.recipientId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusDisplayName(record.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor(record.status)
                )
                if (record.errorReason != null) {
                    Text(
                        text = record.errorReason!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = formatTimestamp(record.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expiration warning
            record.expiresAt?.let { expiresAt ->
                val now = System.currentTimeMillis() / 1000
                if (expiresAt <= now.toULong()) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Expired",
                        tint = MaterialTheme.colorScheme.error
                    )
                } else if (expiresAt - now.toULong() < 86400u) {
                    Text(
                        text = "Expires soon",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
fun FailedDeliveryCard(
    record: MobileDeliveryRecord,
    onRetry: (String) -> Unit
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeliveryStatusIcon(record.status)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.recipientId.take(16) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (record.errorReason != null) {
                        Text(
                            text = record.errorReason!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Button(
                onClick = { onRetry(record.messageId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(localizationManager.t("action.retry"))
            }
        }
    }
}

@Composable
fun RetryEntryCard(entry: MobileRetryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Retry pending",
                tint = MaterialTheme.colorScheme.tertiary
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.recipientId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Attempt ${entry.attempt} of ${entry.maxAttempts}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Next retry: ${formatTimestamp(entry.nextRetry)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (entry.isMaxExceeded) {
                Text(
                    text = "Max attempts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun DeliveryStatusIcon(status: MobileDeliveryStatus) {
    val (icon, color) = when (status) {
        MobileDeliveryStatus.QUEUED -> Icons.Default.Schedule to Color.Gray
        MobileDeliveryStatus.SENT -> Icons.Default.ArrowUpward to Color.Blue
        MobileDeliveryStatus.STORED -> Icons.Default.CheckCircleOutline to Color.Cyan
        MobileDeliveryStatus.DELIVERED -> Icons.Default.CheckCircle to Color.Green
        MobileDeliveryStatus.EXPIRED -> Icons.Default.Warning to Color(0xFFFFA000)
        MobileDeliveryStatus.FAILED -> Icons.Default.Error to Color.Red
    }
    Icon(icon, contentDescription = statusDisplayName(status), tint = color)
}

@Composable
fun EmptyDeliveryContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DeliverySummaryCard(summary: MobileDeliverySummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Progress bar
            LinearProgressIndicator(
                progress = { summary.progressPercent.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    summary.isFullyDelivered -> MaterialTheme.colorScheme.primary
                    summary.failedDevices > 0u -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
            )

            // Status text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Delivered to ${summary.deliveredDevices} of ${summary.totalDevices} devices",
                    style = MaterialTheme.typography.bodySmall
                )
                if (summary.failedDevices > 0u) {
                    Text(
                        text = "${summary.failedDevices} failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusIndicator(status: MobileDeliveryStatus) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (status) {
                MobileDeliveryStatus.QUEUED -> Icons.Default.Schedule
                MobileDeliveryStatus.SENT -> Icons.Default.ArrowUpward
                MobileDeliveryStatus.STORED -> Icons.Default.CheckCircleOutline
                MobileDeliveryStatus.DELIVERED -> Icons.Default.CheckCircle
                MobileDeliveryStatus.EXPIRED -> Icons.Default.Warning
                MobileDeliveryStatus.FAILED -> Icons.Default.Error
            },
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = statusColor(status)
        )
        Text(
            text = statusDisplayName(status),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(status)
        )
    }
}

private fun statusDisplayName(status: MobileDeliveryStatus): String = when (status) {
    MobileDeliveryStatus.QUEUED -> "Queued"
    MobileDeliveryStatus.SENT -> "Sent"
    MobileDeliveryStatus.STORED -> "Stored"
    MobileDeliveryStatus.DELIVERED -> "Delivered"
    MobileDeliveryStatus.EXPIRED -> "Expired"
    MobileDeliveryStatus.FAILED -> "Failed"
}

@Composable
private fun statusColor(status: MobileDeliveryStatus): Color = when (status) {
    MobileDeliveryStatus.QUEUED -> Color.Gray
    MobileDeliveryStatus.SENT -> Color.Blue
    MobileDeliveryStatus.STORED -> Color.Cyan
    MobileDeliveryStatus.DELIVERED -> Color.Green
    MobileDeliveryStatus.EXPIRED -> Color(0xFFFFA000)
    MobileDeliveryStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun formatTimestamp(timestamp: ULong): String {
    val date = Date(timestamp.toLong() * 1000)
    val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return format.format(date)
}
