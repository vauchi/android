// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Instrumented tests for [PlatformKeychainBridge] — MobilePlatformKeychain adapter
 * over Android KeyStore. Runs on device/emulator where KeyStore is available.
 */
@RunWith(AndroidJUnit4::class)
class PlatformKeychainBridgeTest {
    private lateinit var bridge: PlatformKeychainBridge
    private val testKeyName = "__bridge_test_key__"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bridge = PlatformKeychainBridge(context)
        try {
            bridge.deleteKey(testKeyName)
        } catch (_: Exception) {
        }
    }

    @After
    fun tearDown() {
        try {
            bridge.deleteKey(testKeyName)
        } catch (_: Exception) {
        }
    }

    @Test
    fun save_then_load_round_trips() {
        val keyData = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        bridge.saveKey(testKeyName, keyData)
        val loaded = bridge.loadKey(testKeyName)

        assertContentEquals(keyData, loaded)
    }

    @Test
    fun load_nonexistent_returns_null() {
        val loaded = bridge.loadKey("__nonexistent_key__")

        assertNull(loaded)
    }

    @Test
    fun delete_then_load_returns_null() {
        bridge.saveKey(testKeyName, byteArrayOf(0x01, 0x02, 0x03))

        bridge.deleteKey(testKeyName)
        val loaded = bridge.loadKey(testKeyName)

        assertNull(loaded)
    }

    @Test
    fun save_overwrites_existing() {
        val original = byteArrayOf(0x01, 0x02)
        val updated = byteArrayOf(0x03, 0x04, 0x05)

        bridge.saveKey(testKeyName, original)
        bridge.saveKey(testKeyName, updated)
        val loaded = bridge.loadKey(testKeyName)

        assertContentEquals(updated, loaded)
    }

    @Test
    fun delete_nonexistent_does_not_throw() {
        bridge.deleteKey("__never_saved__")
    }

    @Test
    fun smk_sized_key_round_trips() {
        val keyData = ByteArray(32) { it.toByte() }

        bridge.saveKey(testKeyName, keyData)
        val loaded = bridge.loadKey(testKeyName)

        assertContentEquals(keyData, loaded)
        assertEquals(32, loaded?.size)
    }
}
