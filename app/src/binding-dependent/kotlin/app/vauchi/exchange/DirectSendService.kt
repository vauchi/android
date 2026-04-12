// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.exchange

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP server for USB cable exchange (ADR-031).
 *
 * Listens for incoming desktop connections, executes VXCH framing
 * protocol, and returns the peer's payload via callback.
 *
 * Android acts as the responder (TCP server) for the USB transport.
 * The desktop initiates the connection over a forwarded ADB port.
 */
class DirectSendService {
    companion object {
        const val DEFAULT_PORT = 19283
        private val MAGIC = byteArrayOf(0x56, 0x58, 0x43, 0x48) // "VXCH"
        private const val VERSION: Byte = 1
        private const val MAX_PAYLOAD = 65536
        private const val TIMEOUT_MS = 10_000
    }

    interface Callback {
        fun onPayloadReceived(data: ByteArray)

        fun onError(error: String)
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun exchange(
        payload: ByteArray,
        isInitiator: Boolean,
        callback: Callback,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ) {
        job =
            scope.launch {
                try {
                    val theirPayload =
                        if (isInitiator) {
                            connectAndExchange(payload)
                        } else {
                            listenAndExchange(payload)
                        }
                    withContext(Dispatchers.Main) {
                        callback.onPayloadReceived(theirPayload)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        callback.onError(e.message ?: "unknown error")
                    }
                }
            }
    }

    fun cancel() {
        job?.cancel()
        serverSocket?.close()
    }

    private fun listenAndExchange(ourPayload: ByteArray): ByteArray {
        val server = ServerSocket(DEFAULT_PORT).also { serverSocket = it }
        server.soTimeout = TIMEOUT_MS
        val socket = server.accept()
        socket.soTimeout = TIMEOUT_MS
        return try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            // Responder: receive first, send second
            val theirPayload = recvVxch(input)
            sendVxch(output, ourPayload)
            theirPayload
        } finally {
            socket.close()
            server.close()
        }
    }

    private fun connectAndExchange(ourPayload: ByteArray): ByteArray {
        val socket = Socket("127.0.0.1", DEFAULT_PORT)
        socket.soTimeout = TIMEOUT_MS
        return try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            // Initiator: send first, receive second
            sendVxch(output, ourPayload)
            recvVxch(input)
        } finally {
            socket.close()
        }
    }

    private fun sendVxch(
        output: DataOutputStream,
        payload: ByteArray,
    ) {
        require(payload.isNotEmpty()) { "empty payload" }
        output.write(MAGIC)
        output.writeByte(VERSION.toInt())
        output.writeInt(payload.size) // big-endian by default in Java
        output.write(payload)
        output.flush()
    }

    private fun recvVxch(input: DataInputStream): ByteArray {
        val magic = ByteArray(4)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "invalid VXCH magic" }
        val version = input.readByte()
        require(version == VERSION) { "unsupported version: $version" }
        val len = input.readInt() // big-endian
        require(len in 1..MAX_PAYLOAD) { "invalid payload length: $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload
    }
}
