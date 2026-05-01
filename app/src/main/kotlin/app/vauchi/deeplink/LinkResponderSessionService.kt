// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.deeplink

import android.util.Log
import app.vauchi.ui.coreui.CoreAppViewModel
import uniffi.vauchi_platform.LinkResponderSessionListener
import uniffi.vauchi_platform.MobileExchangeCommand
import uniffi.vauchi_platform.MobileLinkResponderFailureReason
import uniffi.vauchi_platform.MobileLinkResponderSession
import uniffi.vauchi_platform.MobileLinkResponderState

/**
 * Bridges the engine-cached [MobileLinkResponderSession] to the Android UI.
 *
 * Lifecycle:
 *
 * 1. [MainActivity] observes `coreScreen?.screenId == "link_responder_waiting"`.
 * 2. Calls [startIfNeeded] — fetches the engine-cached session via
 *    [PlatformAppEngine.currentLinkResponderSession][uniffi.vauchi_platform.PlatformAppEngine.currentLinkResponderSession],
 *    attaches `this` as the listener, calls `start()`. Idempotent.
 * 3. Cycle thread emits typed callbacks (`onStateChanged`, `onCommands`,
 *    `onFinalized`, `onFailed`, `onSessionEnded`) from a background thread.
 *    Listener implementations dispatch back to [coreVM] via the public
 *    [CoreAppViewModel.showToast] / [CoreAppViewModel.navigateBack]
 *    surfaces — both are safe to call from any thread because they marshal
 *    onto `viewModelScope.launch { ... }` themselves.
 * 4. When the user navigates away from the responder screen, core's
 *    `after_screen_transition` cancel-on-leave fires
 *    `MobileLinkResponderSession::cancel`, surfacing
 *    `onFailed(Cancelled)` + `onSessionEnded`. `Cancelled` is a silent
 *    terminal — no toast.
 *
 * The current draft surfaces toasts via [CoreAppViewModel.showToast] and
 * triggers [CoreAppViewModel.navigateBack] on terminal events. The
 * `onCommands` callback is a TODO (the Android `RelayEscrow*` HTTP
 * client is not yet implemented — see the existing TODOs in
 * [app.vauchi.exchange.ExchangeCommandHandler]). Until that lands the
 * 5-minute polling deadline fires `PollingTimedOut`. The "could not
 * save contact" gap on success is tracked as a Phase 2 follow-up of
 * `_private/docs/problems/2026-04-27-deep-link-responder-flow` —
 * persistence will move into the cycle thread (mirroring
 * `MobileMultiStageSession::with_persistence`) so `on_finalized`
 * surfaces a contact name and the contact lands in storage from core
 * (ADR-021, no frontend persistence).
 */
class LinkResponderSessionService(
    private val coreVM: CoreAppViewModel,
) : LinkResponderSessionListener {
    private var session: MobileLinkResponderSession? = null

    /**
     * Idempotent. Pulls the engine-cached session, registers `this` as
     * the listener, and spawns the cycle thread. A second call while a
     * session is already in flight is a no-op.
     */
    fun startIfNeeded() {
        if (session != null) return
        try {
            val s =
                coreVM.platformAppEngine().currentLinkResponderSession() ?: run {
                    Log.w(TAG, "no engine-cached session — wrong screen?")
                    return
                }
            s.setListener(this)
            s.start()
            session = s
        } catch (e: Exception) {
            Log.e(TAG, "failed to start: $e")
        }
    }

    /**
     * Tear down. Idempotent — safe to call when no session is active.
     * Used as a defensive cleanup when the screen disappears even if
     * core's `after_screen_transition` cancel-on-leave already ran.
     */
    fun stop() {
        session?.cancel()
        session = null
    }

    override fun onStateChanged(state: MobileLinkResponderState) {
        // Single-screen design — the waiting screen does not branch on
        // sub-state. This is the hook for a future progress indicator.
    }

    override fun onCommands(commands: List<MobileExchangeCommand>) {
        // TODO(2026-04-27 deep-link-responder Phase 2): dispatch
        // RelayEscrowDeposit / RelayEscrowCheck / RelayEscrowRetrieve
        // via a relay HTTP client. The existing Android
        // ExchangeCommandHandler carries TODOs for these; once that
        // lands, route here too. Until then the cycle thread's commands
        // have no platform handler and the polling deadline (~5 min)
        // fires `PollingTimedOut`.
    }

    override fun onFinalized(cardBytes: ByteArray) {
        // FOLLOW-UP: persist the contact via core. Phase 1.7 will move
        // persistence into the cycle thread (mirroring
        // `MobileMultiStageSession::with_persistence`), at which point
        // the listener will surface `onFinalized(contactName)` instead
        // of raw `cardBytes` and the toast can include the peer name.
        // For now, surface a generic success toast.
        coreVM.showToast("Contact added")
        coreVM.navigateBack()
    }

    override fun onFailed(reason: MobileLinkResponderFailureReason) {
        val message: String? =
            when (reason) {
                is MobileLinkResponderFailureReason.PollingTimedOut -> {
                    "The sender hasn't responded yet"
                }

                is MobileLinkResponderFailureReason.DepositRejected -> {
                    "This link has already been accepted"
                }

                is MobileLinkResponderFailureReason.DecryptError -> {
                    "Could not decrypt the response"
                }

                // Silent terminal — user-initiated or navigate-back
                // cancellation. The navigate-back already happened or
                // is about to.
                is MobileLinkResponderFailureReason.Cancelled -> {
                    null
                }
            }
        message?.let {
            coreVM.showToast(it)
            coreVM.navigateBack()
        }
    }

    override fun onSessionEnded() {
        // Cycle thread has finished. Drop the local reference — the
        // engine slot was already cleared by `after_screen_transition`
        // cancel-on-leave when navigation left the responder screen.
        session = null
    }

    companion object {
        private const val TAG = "LinkResponder"
    }
}
