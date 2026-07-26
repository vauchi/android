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
 * 2. Enters the NFC exchange on first composition via
 *    `UserAction.ListItemSelected("category:fun", "mode:tap_tap")`. Core
 *    then presents the Send/Receive role choice
 *    (`ExchangeStep::NfcRoleSelection`) as a `ScreenModel` this
 *    `CoreScreenView` renders — the role choice is core-driven (ADR-043/044).
 *    Send routes to `start_taptap_mode`, Receive to `start_nfc_receive_mode`.
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
fun NfcTapExchangeScreen(coreAppViewModel: CoreAppViewModel) {
    // Enter the NFC exchange on first composition by selecting TapTap. Core
    // then presents the Send/Receive role choice
    // (`ExchangeStep::NfcRoleSelection`) as a ScreenModel this CoreScreenView
    // renders — the role choice is core-driven (ADR-043/044), not here.
    // TODO(HUMBLE): T/W, P1. Hardcodes mode/category item ids to enter exchange.
    // Fix: core exposes a single start_nfc_exchange action. (see _private
    // problem record 2026-07-06-mobile-domain-shell-violations)
    LaunchedEffect(Unit) {
        coreAppViewModel.handleAction(
            UserAction.ListItemSelected(
                componentId = "category:fun",
                itemId = "mode:tap_tap",
            ),
        )
    }

    // Forward system back to core as the engine-level cancel event.
    // TODO(HUMBLE): T, P1. Mints generic "cancel" action id. Fix: core
    // exposes cancel action id in ScreenModel actions. (see _private problem
    // record 2026-07-06-mobile-domain-shell-violations)
    BackHandler {
        coreAppViewModel.handleAction(UserAction.ActionPressed(actionId = "cancel"))
    }

    // TODO(HUMBLE): W, P2. Passes domain screen name "Exchange".
    // (see _private problem record 2026-07-06-mobile-domain-shell-violations)
    CoreScreenView(
        viewModel = coreAppViewModel,
        screenName = "Exchange",
        modifier = Modifier.fillMaxSize(),
        // Rendered inside MainActivity's Scaffold whose top bar already
        // shows `screen.title`; the in-body header must not repeat it.
        titleShownInTopBar = true,
    )
}
