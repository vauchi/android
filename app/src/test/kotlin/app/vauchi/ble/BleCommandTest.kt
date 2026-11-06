// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.vauchi_platform.MobileBleLinkDirection

class BleCommandTest {
    @Test
    fun `disconnect identifies exactly one physical link`() {
        val command =
            BleCommand.Disconnect(
                deviceId = "AA:BB:CC:DD:EE:FF",
                direction = MobileBleLinkDirection.OUTBOUND,
            )

        assertEquals("AA:BB:CC:DD:EE:FF", command.deviceId)
        assertEquals(MobileBleLinkDirection.OUTBOUND, command.direction)
    }

    @Test
    fun `write preserves target link and direction`() {
        val command =
            BleCommand.Write(
                deviceId = "AA:BB:CC:DD:EE:FF",
                direction = MobileBleLinkDirection.INBOUND,
                uuid = BleUuids.HANDSHAKE_NOTIFY,
                data = byteArrayOf(1, 2),
            )

        assertEquals("AA:BB:CC:DD:EE:FF", command.deviceId)
        assertEquals(MobileBleLinkDirection.INBOUND, command.direction)
    }

    @Test
    fun `read preserves target link and direction`() {
        val command =
            BleCommand.Read(
                deviceId = "AA:BB:CC:DD:EE:FF",
                direction = MobileBleLinkDirection.OUTBOUND,
                uuid = BleUuids.EXCHANGE_PAYLOAD,
            )

        assertEquals("AA:BB:CC:DD:EE:FF", command.deviceId)
        assertEquals(MobileBleLinkDirection.OUTBOUND, command.direction)
    }

    @Test
    fun `a stop asks for no permission when nothing is queued`() {
        assertFalse(BleCommand.StopAdvertising.mustWaitForPermission(commandsQueued = false))
        assertFalse(BleCommand.StopScanning.mustWaitForPermission(commandsQueued = false))
    }

    @Test
    fun `a stop keeps its place behind a queued start`() {
        assertTrue(BleCommand.StopAdvertising.mustWaitForPermission(commandsQueued = true))
        assertTrue(BleCommand.StopScanning.mustWaitForPermission(commandsQueued = true))
    }

    @Test
    fun `a start always waits for permission`() {
        val start = BleCommand.StartScan(serviceUuid = BleUuids.SERVICE)

        assertTrue(start.mustWaitForPermission(commandsQueued = false))
    }
}
