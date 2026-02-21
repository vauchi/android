// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vauchi.util.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Status of the proximity verification flow.
 */
enum class ProximityStatus {
    /** Checking device capability for ultrasonic audio. */
    CHECKING,

    /** Ultrasonic verification is in progress. */
    ULTRASONIC_IN_PROGRESS,

    /** Ultrasonic failed or unavailable; manual confirmation required. */
    MANUAL_REQUIRED,

    /** Proximity has been verified (ultrasonic or manual). */
    VERIFIED,

    /** Verification failed. */
    FAILED,
}

/**
 * Reusable Compose component for proximity verification.
 *
 * Tries ultrasonic audio first, falls back to manual confirmation.
 * Used by both device linking (P0-1) and contact exchange (P0-3).
 *
 * @param challenge The proximity challenge bytes to emit/verify.
 * @param proximitySupported Whether the device supports proximity verification.
 * @param proximityCapability Device capability: "full", "emit_only", "receive_only", or "none".
 * @param onEmitChallenge Callback to emit the ultrasonic challenge. Returns true on success.
 * @param onListenForResponse Callback to listen for ultrasonic response. Returns response bytes or null on timeout.
 * @param onStopVerification Callback to stop any ongoing verification and clean up resources.
 * @param onVerified Callback invoked when proximity has been verified (ultrasonic or manual).
 * @param onCancel Callback invoked when the user cancels verification.
 */
@Composable
fun ProximityVerification(
    challenge: ByteArray,
    proximitySupported: Boolean,
    proximityCapability: String,
    onEmitChallenge: (ByteArray) -> Boolean,
    onListenForResponse: (ULong) -> ByteArray?,
    onStopVerification: () -> Unit,
    onVerified: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var status by remember { mutableStateOf(ProximityStatus.CHECKING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Attempt ultrasonic verification on composition
    LaunchedEffect(challenge, proximityCapability) {
        status = ProximityStatus.CHECKING

        if (!proximitySupported || proximityCapability == "none") {
            status = ProximityStatus.MANUAL_REQUIRED
            return@LaunchedEffect
        }

        if (proximityCapability == "full" || proximityCapability == "emit_only") {
            status = ProximityStatus.ULTRASONIC_IN_PROGRESS

            try {
                val success =
                    withContext(Dispatchers.IO) {
                        onEmitChallenge(challenge)
                    }

                if (success) {
                    status = ProximityStatus.VERIFIED
                    onVerified()
                } else {
                    errorMessage = "Ultrasonic signal could not be sent"
                    status = ProximityStatus.MANUAL_REQUIRED
                }
            } catch (e: Exception) {
                errorMessage = e.message
                status = ProximityStatus.MANUAL_REQUIRED
            }
        } else {
            // receive_only or unsupported — go straight to manual
            status = ProximityStatus.MANUAL_REQUIRED
        }
    }

    // Clean up on disposal
    DisposableEffect(Unit) {
        onDispose {
            onStopVerification()
        }
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Proximity verification"
                },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (status) {
                        ProximityStatus.VERIFIED -> MaterialTheme.colorScheme.primaryContainer
                        ProximityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title
            Text(
                text = localizationManager.t("action.verify"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )

            // Status-dependent content
            when (status) {
                ProximityStatus.CHECKING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Checking device capabilities...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProximityStatus.ULTRASONIC_IN_PROGRESS -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = localizationManager.t("exchange.proximity_ready"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Hold devices close together...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProximityStatus.MANUAL_REQUIRED -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (errorMessage != null) {
                        Text(
                            text = "Automatic verification unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "Ultrasonic not available on this device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = "Please confirm that both devices are physically nearby.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = {
                            status = ProximityStatus.VERIFIED
                            onVerified()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(localizationManager.t("action.confirm"))
                    }
                }

                ProximityStatus.VERIFIED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Verified",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Proximity verified",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                ProximityStatus.FAILED -> {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Failed",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Verification failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    errorMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    Button(
                        onClick = {
                            status = ProximityStatus.MANUAL_REQUIRED
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(localizationManager.t("action.retry"))
                    }
                }
            }

            // Cancel button — available in all states except VERIFIED
            AnimatedVisibility(
                visible = status != ProximityStatus.VERIFIED,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OutlinedButton(
                    onClick = {
                        onStopVerification()
                        onCancel()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(localizationManager.t("action.cancel"))
                }
            }
        }
    }
}
