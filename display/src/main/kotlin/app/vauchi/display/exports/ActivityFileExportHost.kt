// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import app.vauchi.contract.Event
import app.vauchi.contract.ExportFileSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

/**
 * Enter on Main with a stable read-only payload until return. Disk work suspends;
 * accepted shared files outlive close so receivers can still read them. Cache
 * expiry is opportunistic, not scheduled deletion or delivery confirmation.
 */
class ActivityFileExportHost internal constructor(
    context: Context,
    activity: () -> ComponentActivity?,
    private val limits: FileExportLimits,
    clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    openOutput: (File) -> OutputStream,
) : AutoCloseable {
    constructor(context: Context, activity: () -> ComponentActivity?, limits: FileExportLimits = FileExportLimits()) :
        this(context, activity, limits, System::currentTimeMillis, Dispatchers.IO, ::FileOutputStream)

    private val provider = AtomicReference<(() -> ComponentActivity?)?>(activity)
    private val active = AtomicReference<Job?>(null)
    private val cache = FileExportCache(context, limits, clock, openOutput)

    suspend fun export(file: ExportFileSpec): Event? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (Looper.myLooper() != Looper.getMainLooper()) return Event.HardwareUnavailable("file_export")
        val metadata =
            try {
                requireActivity()
                ExportMetadata.from(file, limits)
            } catch (error: FileExportFailure) {
                return failureEvent(error)
            } catch (_: Exception) {
                return Event.HardwareUnavailable("file_export")
            }
        if (!permit.tryAcquire()) return Event.HardwareError("file_export_busy", "file_export")
        val operation = Job(caller[Job])
        if (!active.compareAndSet(null, operation) || provider.get() == null) {
            active.compareAndSet(operation, null)
            operation.complete()
            permit.release()
            return Event.HardwareUnavailable("file_export")
        }
        var prepared: PreparedFileExport? = null
        var published = false
        var result: Event? = null
        var cancellation: CancellationException? = null
        try {
            withContext(operation) {
                runInterruptible(io) {
                    // Capture ownership before prompt cancellation can discard the IO result (#773).
                    prepared =
                        cache.prepare(file, metadata) {
                            operation.ensureActive()
                            if (provider.get() == null ||
                                Thread.currentThread().isInterrupted
                            ) {
                                throw CancellationException("File export closed")
                            }
                        }
                }
                currentCoroutineContext().ensureActive()
                val owner = requireActivity()
                val uri = prepared!!.uri
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = metadata.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                currentCoroutineContext().ensureActive()
                owner.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                published = true
            }
        } catch (error: CancellationException) {
            cancellation = error
        } catch (error: FileExportFailure) {
            result = failureEvent(error)
        } catch (_: Exception) {
            result = Event.HardwareError("file_export_failed", "file_export")
        } finally {
            try {
                if (!published) prepared?.let { output -> withContext(NonCancellable + io) { cache.discard(output.file) } }
            } catch (_: Exception) {
                if (cancellation == null) result = Event.HardwareError("file_export_cleanup_failed", "file_export")
            } finally {
                active.compareAndSet(operation, null)
                operation.complete()
                permit.release()
            }
        }
        cancellation?.let { throw it }
        return result
    }

    private fun failureEvent(error: FileExportFailure): Event =
        if (error.code ==
            "file_export_unavailable"
        ) {
            Event.HardwareUnavailable("file_export")
        } else {
            Event.HardwareError(error.code, "file_export")
        }

    private fun requireActivity(): ComponentActivity {
        val source = provider.get() ?: throw FileExportFailure("file_export_unavailable")
        val owner = source() ?: throw FileExportFailure("file_export_unavailable")
        if (provider.get() == null || owner.isFinishing || owner.isDestroyed || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            throw FileExportFailure("file_export_unavailable")
        }
        return owner
    }

    override fun close() {
        provider.set(null)
        active.getAndSet(null)?.cancel()
    }

    private companion object {
        val permit = Semaphore(1)
    }
}
