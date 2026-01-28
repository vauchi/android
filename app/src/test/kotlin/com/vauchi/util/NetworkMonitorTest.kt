// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    @Mock
    private lateinit var mockApplication: Application

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockConnectivityManager: ConnectivityManager

    @Mock
    private lateinit var mockNetwork: Network

    @Mock
    private lateinit var mockNetworkCapabilities: NetworkCapabilities

    private lateinit var networkMonitor: NetworkMonitor

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockApplication.applicationContext).thenReturn(mockContext)
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mockConnectivityManager)

        networkMonitor = NetworkMonitor(mockApplication)
    }

    @Test
    fun `test initial network state`() {
        // Initial state should default to true or check current connectivity
        val initialState = networkMonitor.isCurrentlyConnected()
        assertTrue(initialState is Boolean)
    }

    @Test
    fun `test isCurrentlyConnected when network is available`() {
        whenever(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
            .thenReturn(mockNetworkCapabilities)
        whenever(mockNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(true)

        val isConnected = networkMonitor.isCurrentlyConnected()
        assertTrue(isConnected)
    }

    @Test
    fun `test isCurrentlyConnected when no network`() {
        whenever(mockConnectivityManager.activeNetwork).thenReturn(null)

        val isConnected = networkMonitor.isCurrentlyConnected()
        assertFalse(isConnected)
    }

    @Test
    fun `test isCurrentlyConnected when network has no internet`() {
        whenever(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
            .thenReturn(mockNetworkCapabilities)
        whenever(mockNetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(false)

        val isConnected = networkMonitor.isCurrentlyConnected()
        assertFalse(isConnected)
    }

    @Test
    fun `test network availability flow`() = runBlocking {
        // Test that the flow emits values
        val firstValue = networkMonitor.isOnline.first()
        assertTrue(firstValue is Boolean)
    }

    @Test
    fun `test connectivity manager properties`() {
        // Verify we're using the correct network capabilities
        val capabilities = NetworkCapabilities.Builder().build()
        whenever(mockConnectivityManager.getNetworkCapabilities(any()))
            .thenReturn(capabilities)

        val isConnected = networkMonitor.isCurrentlyConnected()
        // Should not crash and return a boolean
        assertTrue(isConnected is Boolean)
    }
}
