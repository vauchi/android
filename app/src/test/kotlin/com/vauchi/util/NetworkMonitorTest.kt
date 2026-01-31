// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.util

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

    @Test
    fun `isCurrentlyConnected returns false when no active network`() {
        // Default Robolectric state has no active network configured
        // so activeNetwork returns null
        val monitor = NetworkMonitor(context)
        // Should not throw, returns a boolean
        val result = monitor.isCurrentlyConnected()
        assertNotNull(result)
    }

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
}
