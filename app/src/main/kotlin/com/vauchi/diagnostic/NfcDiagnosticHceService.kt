// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.diagnostic

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * Diagnostic HCE service for NFC transport testing.
 *
 * Responds to a diagnostic AID with echo/timing operations.
 * Separate from VauchiHceService — no auth or identity required.
 *
 * Instruction set:
 * - SELECT AID (F0564155434849D1) → SW_OK
 * - INS=0xD0 (ECHO): returns received data + 8-byte timestamp
 * - INS=0xD1 (PAYLOAD_TEST): returns received data back (echo)
 */
class NfcDiagnosticHceService : HostApduService() {
    companion object {
        private const val TAG = "NfcDiag"

        /** Diagnostic AID: Vauchi AID + D1 suffix to distinguish from exchange AID */
        val DIAGNOSTIC_AID =
            byteArrayOf(
                0xF0.toByte(),
                0x56,
                0x41,
                0x55,
                0x43,
                0x48,
                0x49,
                0xD1.toByte(),
            )

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_WRONG_DATA = byteArrayOf(0x6A.toByte(), 0x80.toByte())

        const val INS_ECHO: Byte = 0xD0.toByte()
        const val INS_PAYLOAD_TEST: Byte = 0xD1.toByte()

        @Volatile
        var active = false
            private set

        @Volatile
        var apduCount = 0
            private set

        fun reset() {
            apduCount = 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = true
        apduCount = 0
        Log.i("Vauchi", "[NFC Diag HCE] Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        active = false
        Log.i("Vauchi", "[NFC Diag HCE] Service destroyed, processed $apduCount APDUs")
    }

    override fun processCommandApdu(
        commandApdu: ByteArray,
        extras: Bundle?,
    ): ByteArray {
        if (commandApdu.size < 4) return SW_WRONG_DATA

        if (isSelectAid(commandApdu)) {
            Log.i("Vauchi", "[NFC Diag HCE] Diagnostic AID selected")
            return SW_OK
        }

        apduCount++
        val ins = commandApdu[1]
        val data = extractData(commandApdu)

        return when (ins) {
            INS_ECHO -> {
                // Echo data back with 8-byte nanosecond timestamp appended
                val timestamp = System.nanoTime()
                val tsBytes = ByteArray(8)
                for (i in 0 until 8) {
                    tsBytes[i] = (timestamp shr (56 - i * 8)).toByte()
                }
                val response = ByteArray(data.size + tsBytes.size + 2)
                System.arraycopy(data, 0, response, 0, data.size)
                System.arraycopy(tsBytes, 0, response, data.size, tsBytes.size)
                response[response.size - 2] = 0x90.toByte()
                response[response.size - 1] = 0x00
                response
            }

            INS_PAYLOAD_TEST -> {
                // Pure echo — return exactly what was received
                val response = ByteArray(data.size + 2)
                System.arraycopy(data, 0, response, 0, data.size)
                response[response.size - 2] = 0x90.toByte()
                response[response.size - 1] = 0x00
                response
            }

            else -> {
                Log.w("Vauchi", "[NFC Diag HCE] Unknown INS: %02x".format(ins))
                SW_WRONG_DATA
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = if (reason == DEACTIVATION_LINK_LOSS) "link_loss" else "deselected"
        Log.i("Vauchi", "[NFC Diag HCE] Deactivated: $reasonStr")
    }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < 6) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return false
        if (apdu[2] != 0x04.toByte() || apdu[3] != 0x00.toByte()) return false
        val aidLen = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + aidLen) return false
        return apdu.sliceArray(5 until 5 + aidLen).contentEquals(DIAGNOSTIC_AID)
    }

    private fun extractData(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return ByteArray(0)
        val lc = apdu[4].toInt() and 0xFF
        if (lc != 0) {
            if (apdu.size < 5 + lc) return ByteArray(0)
            return apdu.sliceArray(5 until 5 + lc)
        } else if (apdu.size >= 7) {
            val extLen = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            if (apdu.size < 7 + extLen) return ByteArray(0)
            return apdu.sliceArray(7 until 7 + extLen)
        }
        return ByteArray(0)
    }
}
