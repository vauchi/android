// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

// Unit tests for the transceive-shim API added in Phase 3a of the
// NFC engine-graduation
// (_private/docs/problems/2026-05-19-nfc-exchange-engine-graduation).
//
// Scope: the parts of activate(payload:callback:) / sendApdu(data:) /
// deactivate() that are testable without a real NFC Tag — i.e., the
// defensive "no tag connected" path, idempotent deactivate, and
// callback wiring for the fresh-service no-op case.
//
// **Out of scope** (mirrors iOS Phase 2 stance per CC-23 + design
// doc §"Risks the executing session should watch"): any test that
// pretends an IsoDep tag is connected. Tag instantiation requires
// the OS-side NFC discovery path; mocking it would couple this
// suite to NfcAdapter internals that drift between Android versions.
// End-to-end transceive verification is deferred to Phase 6
// physical-device cycles.

package app.vauchi.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.vauchi_platform.MobileEvent

@RunWith(RobolectricTestRunner::class)
class NfcReaderServiceTransceiveShimTest {
    private lateinit var service: NfcReaderService

    @Before
    fun setUp() {
        service = NfcReaderService()
    }

    /**
     * Calling [NfcReaderService.sendApdu] before [NfcReaderService.activate]
     * has registered a callback is a no-op — there is no engine
     * consumer to surface a hardware event to. The service must not
     * crash; a stray sendApdu (e.g. from a late drain) is silently
     * dropped.
     */
    @Test
    fun `sendApdu on fresh service is a silent no-op`() {
        // No callback registered — sendApdu should simply return.
        service.sendApdu(byteArrayOf(0x01, 0x02, 0x03))
        // Reaching here without a thrown exception is the assertion.
        assertTrue("sendApdu on fresh service must not throw", true)
    }

    /**
     * Once [NfcReaderService.activate] has registered a callback, a
     * subsequent [NfcReaderService.sendApdu] without a connected tag
     * must surface [MobileEvent.HardwareError] via the callback —
     * core relies on this to fail-fast rather than wedge the engine
     * waiting on a transceive that will never fire.
     */
    @Test
    fun `sendApdu after activate without tag fires HardwareError NFC`() {
        val received = mutableListOf<MobileEvent>()
        service.activate(byteArrayOf(0x01, 0x02, 0x03)) { event ->
            received.add(event)
        }

        service.sendApdu(byteArrayOf(0x04, 0x05))

        assertEquals(
            "Expected exactly one event after sendApdu-without-tag",
            1,
            received.size,
        )
        val event = received.first()
        assertTrue(
            "Expected HardwareError, got $event",
            event is MobileEvent.HardwareError &&
                event.transport == "NFC" &&
                event.error == "no tag connected",
        )
    }

    /**
     * [NfcReaderService.deactivate] must be safe to call multiple
     * times consecutively — the engine emits `NfcDeactivate` on
     * screen exit, error paths, and successful completion; any of
     * those paths can race with a second emission. Idempotence is a
     * correctness requirement.
     */
    @Test
    fun `deactivate is idempotent across multiple calls`() {
        service.activate(byteArrayOf()) { _ -> }
        service.deactivate()
        service.deactivate()
        // No exception thrown across the cycle.
        // Subsequent sendApdu after deactivate-deactivate is the
        // no-callback no-op case (callback was cleared by the first
        // deactivate).
        service.sendApdu(byteArrayOf(0x01))
        assertTrue("deactivate × 2 + post-deactivate sendApdu must not throw", true)
    }
}
