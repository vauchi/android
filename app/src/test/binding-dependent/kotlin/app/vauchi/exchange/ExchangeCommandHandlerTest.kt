// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import android.content.Context
import app.vauchi.ble.BleExchangeService
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.vauchi_platform.MobileCommand
import uniffi.vauchi_platform.MobileEvent
import uniffi.vauchi_platform.MobileExchangeSession

/**
 * Unit tests for [ExchangeCommandHandler] — verifies that BLE-class
 * `MobileCommand` variants drained from the session route to the
 * correct [BleExchangeService] method, and that the unimplemented
 * `BleStartAdvertising` path reports `HardwareUnavailable("BLE-advertise")`
 * back to the session (Phase 2 audit reference; tracked as F9 in the BLE
 * migration record's plan — pre-condition for Phase 4.B once advertise is
 * wired in Phase 2.5).
 *
 * Tests inject a mock [BleExchangeService] via the factory parameter on
 * the handler ctor and a mock [MobileExchangeSession] whose
 * `drainPendingCommands()` is scripted per case. Each test mocks two
 * drain calls: the first returns the command under test, the second
 * returns an empty list to terminate the recursive `drainAndDispatch`
 * cascade triggered by `applyHardwareEvent` callbacks.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeCommandHandlerTest {
    private lateinit var session: MobileExchangeSession
    private lateinit var bleService: BleExchangeService
    private lateinit var context: Context
    private lateinit var handler: ExchangeCommandHandler

    @Before
    fun setUp() {
        session = mock()
        bleService = mock()
        context = RuntimeEnvironment.getApplication()
        handler =
            ExchangeCommandHandler(
                session = session,
                context = context,
                bleServiceFactory = { _, _ -> bleService },
            )
    }

    // ── BLE scan ──────────────────────────────────────────────────

    @Test
    fun `BleStartScanning routes to bleService startScanning with serviceUuid`() {
        val uuid = "0000180a-0000-1000-8000-00805f9b34fb"
        whenever(session.drainPendingCommands())
            .thenReturn(listOf(MobileCommand.BleStartScanning(uuid)))
            .thenReturn(emptyList())

        handler.drainAndDispatch()

        verify(bleService).startScanning(uuid)
    }

    // ── BLE GATT connect ──────────────────────────────────────────

    @Test
    fun `BleConnect routes to bleService connect with deviceId`() {
        val deviceId = "AA:BB:CC:DD:EE:FF"
        whenever(session.drainPendingCommands())
            .thenReturn(listOf(MobileCommand.BleConnect(deviceId)))
            .thenReturn(emptyList())

        handler.drainAndDispatch()

        verify(bleService).connect(deviceId)
    }

    // ── BLE GATT write ────────────────────────────────────────────

    @Test
    fun `BleWriteCharacteristic routes to bleService writeCharacteristic with uuid+data`() {
        val uuid = "00002a4d-0000-1000-8000-00805f9b34fb"
        val data = byteArrayOf(0x01, 0x02, 0x03)
        whenever(session.drainPendingCommands())
            .thenReturn(listOf(MobileCommand.BleWriteCharacteristic(uuid, data)))
            .thenReturn(emptyList())

        handler.drainAndDispatch()

        verify(bleService).writeCharacteristic(uuid, data)
    }

    // ── BLE GATT read ─────────────────────────────────────────────

    @Test
    fun `BleReadCharacteristic routes to bleService readCharacteristic with uuid`() {
        val uuid = "00002a4d-0000-1000-8000-00805f9b34fb"
        whenever(session.drainPendingCommands())
            .thenReturn(listOf(MobileCommand.BleReadCharacteristic(uuid)))
            .thenReturn(emptyList())

        handler.drainAndDispatch()

        verify(bleService).readCharacteristic(uuid)
    }

    // ── BLE GATT disconnect ───────────────────────────────────────

    @Test
    fun `BleDisconnect routes to bleService disconnect`() {
        whenever(session.drainPendingCommands())
            .thenReturn(listOf(MobileCommand.BleDisconnect))
            .thenReturn(emptyList())

        handler.drainAndDispatch()

        verify(bleService).disconnect()
    }

    // ── BLE advertising (F9 — currently reportUnavailable) ────────

    @Test
    fun `BleStartAdvertising fires HardwareUnavailable BLE-advertise on session`() {
        // Tracks the F9 advertise gap (see Phase 2.5 in the BLE
        // migration plan). Until the peripheral mode is wired, the
        // handler must signal unavailable so the engine can fall back
        // gracefully rather than waiting on a missing BLE adapter.
        whenever(session.drainPendingCommands())
            .thenReturn(
                listOf(
                    MobileCommand.BleStartAdvertising(
                        serviceUuid = "0000180a-0000-1000-8000-00805f9b34fb",
                        payload = byteArrayOf(),
                    ),
                ),
            ).thenReturn(emptyList())

        handler.drainAndDispatch()

        val captor = argumentCaptor<MobileEvent>()
        verify(session, times(1)).applyHardwareEvent(captor.capture())
        val event = captor.firstValue
        assertTrue(
            "Expected HardwareUnavailable, got $event",
            event is MobileEvent.HardwareUnavailable &&
                event.transport == "BLE-advertise",
        )
        verify(bleService, never()).startScanning(any())
    }

    // ── Permission denial passthrough ──────────────────────────────

    @Test
    fun `reportPermissionDenied fires PermissionDenied with transport on session`() {
        // No drained command — direct call.
        whenever(session.drainPendingCommands()).thenReturn(emptyList())

        handler.reportPermissionDenied("BLE")

        val captor = argumentCaptor<MobileEvent>()
        verify(session).applyHardwareEvent(captor.capture())
        val event = captor.firstValue
        assertTrue(
            "Expected PermissionDenied, got $event",
            event is MobileEvent.PermissionDenied &&
                event.transport == "BLE",
        )
    }
}
