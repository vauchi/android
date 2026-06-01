// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.nfc.NfcAdapter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.vauchi.util.LocalizationManager

enum class ExchangeMode {
    QR,
    NFC,
}

/**
 * Mode picker screen for the exchange tab. Shows QR Code (always available)
 * and NFC (if the device has NFC hardware).
 *
 * The NFC **role** choice (Send / Receive) is NOT here — it is core-driven
 * (`ExchangeStep::NfcRoleSelection`, ADR-043/044): tapping NFC enters the
 * engine via `mode:tap_tap`, and core presents the Send/Receive choice as a
 * `ScreenModel` the humble renderer draws.
 *
 * Tapping a card invokes [onModeSelected] with the chosen [ExchangeMode].
 * The caller handles navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeModePicker(onModeSelected: (ExchangeMode) -> Unit) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }
    val hasNfc = remember { NfcAdapter.getDefaultAdapter(context) != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("exchange.choose_method")) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("exchange_mode_picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExchangeModeCard(
                icon = Icons.Default.QrCode,
                title = localizationManager.t("exchange.mode.qr"),
                subtitle = localizationManager.t("exchange.mode.qr_description"),
                unavailableLabel = localizationManager.t("exchange.mode.unavailable"),
                enabled = true,
                testTag = "exchange_mode_qr",
                onClick = { onModeSelected(ExchangeMode.QR) },
            )

            ExchangeModeCard(
                icon = Icons.Default.Nfc,
                title = localizationManager.t("exchange.mode.nfc"),
                subtitle = localizationManager.t("exchange.mode.nfc_description"),
                unavailableLabel = localizationManager.t("exchange.mode.unavailable"),
                enabled = hasNfc,
                testTag = "exchange_mode_nfc",
                onClick = { onModeSelected(ExchangeMode.NFC) },
            )
        }
    }
}

@Composable
private fun ExchangeModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    unavailableLabel: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .testTag(testTag),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (enabled) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(40.dp),
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
                Text(
                    text = if (enabled) subtitle else unavailableLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else 0.38f,
                        ),
                )
            }
        }
    }
}
