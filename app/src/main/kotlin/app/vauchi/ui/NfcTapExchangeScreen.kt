// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.vauchi.ui.coreui.CoreAppViewModel
import app.vauchi.ui.coreui.CoreScreenView
import app.vauchi.ui.coreui.UserAction

/**
 * Phase 4 of `_private/docs/problems/2026-05-19-nfc-exchange-engine-graduation`.
 *
 * Pure Humble UI shell — renders the NFC (TapTap) exchange via
 * [CoreScreenView] over the core-owned `ExchangeEngine`. The 3-phase
 * handshake state lives in core's `NfcExchangeFlow`
 * (`core/vauchi-app/src/ui/exchange/nfc.rs`); APDU dispatch routes
 * through `CoreAppViewModel.handleExchangeCommands` → `dispatchNfcCommand`
 * to `NfcReaderService` (initiator) or the `VauchiHceService`
 * binder-block path (responder).
 *
 * Per ADR-021/043 this composable holds no domain state, makes no
 * navigation decisions, and references no domain types. It only:
 *
 * 1. Renders whatever core says via [CoreScreenView].
 * 2. Emits the chosen NFC role on first composition via
 *    `UserAction.ListItemSelected("category:fun", modeItemId)`. The role
 *    is picked in `ExchangeModePicker`: `"mode:tap_tap"` (Send) routes to
 *    `start_taptap_mode`, which constructs an initiator `NfcExchangeFlow`
 *    and emits `Command::NfcActivate { payload: key_offer }`;
 *    `"mode:tap_tap_receive"` (Receive) routes to `start_nfc_receive_mode`,
 *    which emits an empty `NfcActivate` so the frontend registers the HCE
 *    responder and the lazy bootstrap spins up the responder flow on the
 *    peer's first tap.
 * 3. Forwards the system back button as the engine-level cancel
 *    `UserAction`. Core decides the next screen; the Activity's
 *    screen-sync layer reflects the result.
 *
 * Replaces the legacy `NfcExchangeScreen.kt` (457 LOC) which owned the
 * 3-phase state machine in Kotlin via `MobileNfcHandshake` and managed
 * its own `NfcAdapter.enableReaderMode` lifecycle. The legacy path
 * violated ADR-031 (frontends produce hardware events, core produces
 * commands).
 */
@Composable
fun NfcTapExchangeScreen(
    coreAppViewModel: CoreAppViewModel,
    modeItemId: String = "mode:tap_tap",
) {
    // Emit the chosen NFC role on first composition: "mode:tap_tap"
    // (Send / initiator) or "mode:tap_tap_receive" (Receive / responder).
    // The engine routes to start_taptap_mode or start_nfc_receive_mode.
    LaunchedEffect(modeItemId) {
        coreAppViewModel.handleAction(
            UserAction.ListItemSelected(
                componentId = "category:fun",
                itemId = modeItemId,
            ),
        )
    }

    // Forward system back to core as the engine-level cancel event.
    BackHandler {
        coreAppViewModel.handleAction(UserAction.ActionPressed(actionId = "cancel"))
    }

    CoreScreenView(
        viewModel = coreAppViewModel,
        screenName = "Exchange",
        modifier = Modifier.fillMaxSize(),
    )
}
