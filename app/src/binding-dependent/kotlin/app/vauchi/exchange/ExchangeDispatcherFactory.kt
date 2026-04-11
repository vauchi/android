// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

/**
 * Registers the real [ExchangeCommandHandler]-backed [ExchangeDispatcher] factory
 * into [ExchangeDispatcherFactory].
 *
 * Called from [app.vauchi.VauchiApplication] (or similar entry point) when compiled
 * with local bindings. Without local bindings, [ExchangeDispatcherFactory] falls back
 * to a no-op dispatcher.
 */
object ExchangeDispatcherRegistrar {
    fun register() {
        ExchangeDispatcherFactory.factory = { session, context ->
            val handler = ExchangeCommandHandler(session, context)
            object : ExchangeDispatcher {
                override fun drainAndDispatch() = handler.drainAndDispatch()

                override fun reportPermissionDenied(transport: String) = handler.reportPermissionDenied(transport)
            }
        }
    }
}
