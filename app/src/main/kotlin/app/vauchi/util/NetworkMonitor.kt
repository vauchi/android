// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                trySend(hasInternet)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        trySend(isCurrentlyConnected())

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()


    /**
     * The address a peer on the same network could reach this device at, or
     * `null` when there is no such network (ADR-070).
     *
     * Watches Wi-Fi and Ethernet only. Cellular is excluded deliberately: a
     * peer cannot be on the same segment as you over a mobile network, and
     * the CGNAT address it hands out is one Core refuses anyway. Internet
     * capability is *not* required either — a LAN with no uplink is exactly
     * where linking without the relay is worth most.
     *
     * `null` is emitted on loss so a stale address is never advertised to a
     * joiner that cannot reach it.
     */
    val localAddress: Flow<String?> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                trySend(selectLocalAddress(linkProperties.linkAddresses.map { it.address }))
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    companion object {
        /**
         * Pick the address a peer on the same segment could reach, or `null`.
         *
         * IPv4 site-local only. Loopback reaches nobody else; link-local
         * (169.254/16) means DHCP failed, so a peer is unlikely to share it;
         * and IPv6 is left out until the listener binds it, so we never
         * advertise somewhere nothing is listening. Anything routable is
         * refused by Core's own bound anyway — sending one would only
         * produce a QR that fails at the far end.
         */
        fun selectLocalAddress(addresses: List<InetAddress>): String? =
            addresses
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
    }

    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
