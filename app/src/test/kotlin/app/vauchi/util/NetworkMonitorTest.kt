// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `constructor does not throw`() {
        val monitor = NetworkMonitor(context)
        assertNotNull(monitor)
    }

    // isCurrentlyConnected test removed: Robolectric's ShadowConnectivityManager
    // does not implement getActiveNetwork()/getNetworkCapabilities() causing
    // NoSuchMethodError. This tests Android framework plumbing, not app logic.

    @Test
    fun `isOnline flow is available`() {
        val monitor = NetworkMonitor(context)
        assertNotNull(monitor.isOnline)
    }

    @Test
    fun `multiple instances share same ConnectivityManager`() {
        val monitor1 = NetworkMonitor(context)
        val monitor2 = NetworkMonitor(context)
        // Both should work without conflict
        assertNotNull(monitor1.isOnline)
        assertNotNull(monitor2.isOnline)
    }

    // ── local address selection (ADR-070) ────────────────────────────

    private fun addr(literal: String): java.net.InetAddress =
        java.net.InetAddress.getByName(literal)

    @Test
    fun `picks the site-local address a peer could reach`() {
        val selected = NetworkMonitor.selectLocalAddress(
            listOf(addr("127.0.0.1"), addr("192.168.1.42"))
        )

        assertEquals("192.168.1.42", selected)
    }

    @Test
    fun `reports no address when nothing on the link is reachable by a peer`() {
        // Loopback reaches nobody else, and 169.254/16 means DHCP failed —
        // advertising either produces a QR that fails at the far end.
        val selected = NetworkMonitor.selectLocalAddress(
            listOf(addr("127.0.0.1"), addr("169.254.10.20"))
        )

        assertNull(selected)
    }

    @Test
    fun `ignores IPv6 until the listener binds it`() {
        // The host socket binds IPv4, so advertising a v6 address would
        // point a joiner somewhere nothing is listening.
        val selected = NetworkMonitor.selectLocalAddress(
            listOf(addr("fd00::1"), addr("10.0.0.7"))
        )

        assertEquals("10.0.0.7", selected)
    }

    @Test
    fun `reports no address for an empty link`() {
        assertNull(NetworkMonitor.selectLocalAddress(emptyList()))
    }
}
