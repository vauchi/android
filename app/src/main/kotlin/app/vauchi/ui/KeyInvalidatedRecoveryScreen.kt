// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vauchi.util.LocalizationManager

/**
 * Shown after the storage layer detected an invalidated KeyStore master
 * key and wiped the local encrypted state. The user has three real
 * paths after this point:
 *
 *  1. **Restore from a backup file** — uses the existing
 *     `RestoreIdentityDialog` flow (also reachable from Welcome's
 *     "I already have an identity" button). Fully recovers identity
 *     and contacts when the user has an exported backup.
 *  2. **Set up a new identity** — routes to the onboarding flow.
 *     The user gets a fresh `public_id`; previously-exchanged contacts
 *     will not recognise this device until a new exchange happens.
 *  3. **Recover from a linked device** — handled by setting up a fresh
 *     identity here and re-linking from the other device's
 *     Settings → Linked Devices. There is no in-flow receiver entry
 *     point on Welcome today, so the screen documents this as a
 *     follow-up after option 2.
 *
 * The screen is only shown when `hadData=true` — when the wipe
 * happened on a true fresh install (no prior storage-key blob), the
 * caller routes directly to `UiState.Onboarding`.
 */
@Composable
fun KeyInvalidatedRecoveryScreen(
    onRestoreFromBackup: () -> Unit,
    onStartFresh: () -> Unit,
) {
    val context = LocalContext.current
    val localizationManager = remember(context) { LocalizationManager.getInstance(context) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Couldn't unlock your data",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                "A change to your device's security keys (for example a new fingerprint, " +
                    "a removed lock screen, or a system reset) made your previous Vauchi " +
                    "data unreadable. Your contacts and identity are stored encrypted on " +
                    "this device — without the original key they can't be recovered here.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Restore from a backup",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        "If you previously exported a backup file, you can restore your " +
                            "identity and contacts here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRestoreFromBackup,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("recovery.restore_backup"),
                ) {
                    Text(localizationManager.t("recovery.key_invalidated.restore_button"))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Set up a new identity",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        "Continue with a fresh identity. Previously-exchanged contacts will " +
                            "need to exchange cards with you again. " +
                            "If another device is still linked, you can re-link it from that " +
                            "device's Settings → Linked Devices after onboarding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onStartFresh,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("recovery.start_fresh"),
                ) {
                    Text(localizationManager.t("recovery.key_invalidated.new_identity_button"))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
