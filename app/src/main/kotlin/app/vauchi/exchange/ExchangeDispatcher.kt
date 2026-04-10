// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.content.Context
import uniffi.vauchi_platform.MobileExchangeSession

/**
 * Thin interface wrapping [ExchangeCommandHandler] so that [BleExchangeScreen]
 * (compiled in the `main` source set) does not depend directly on types from
 * the `binding-dependent` source set.
 *
 * The concrete implementation is registered at startup via [ExchangeDispatcherFactory]
 * in the `binding-dependent` source set (local-bindings builds only).
 * Falls back to a no-op when local bindings are unavailable.
 */
interface ExchangeDispatcher {
    fun drainAndDispatch()
}

/**
 * Singleton factory that creates [ExchangeDispatcher] instances.
 *
 * The real factory is injected by [ExchangeDispatcherRegistrar] (binding-dependent).
 * Defaults to a no-op so [BleExchangeScreen] compiles in all build variants.
 */
object ExchangeDispatcherFactory {
    internal var factory: (MobileExchangeSession, Context) -> ExchangeDispatcher = { _, _ ->
        object : ExchangeDispatcher {
            override fun drainAndDispatch() {
                // No-op: BLE command dispatch requires local bindings
            }
        }
    }

    fun create(
        session: MobileExchangeSession,
        context: Context,
    ): ExchangeDispatcher = factory(session, context)
}
