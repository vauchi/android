// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.vauchi.contract.ExportFileSpec
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

internal class PreparedFileExport(
    val file: File,
    val uri: Uri,
)

internal class FileExportCache(
    context: Context,
    private val limits: FileExportLimits,
    private val clock: () -> Long,
    private val openOutput: (File) -> OutputStream,
) {
    private val application = context.applicationContext
    private val ownedName = Regex("export-[A-Za-z0-9_-]+\\.bin")

    fun prepare(
        file: ExportFileSpec,
        metadata: ExportMetadata,
        checkActive: () -> Unit,
    ): PreparedFileExport {
        val base = application.cacheDir.canonicalFile
        val directory = File(base, "vauchi-display-exports")
        val lockFile = File(base, "vauchi-display-export.lock")
        if (directory.canonicalFile != directory ||
            lockFile.canonicalFile != lockFile
        ) {
            throw FileExportFailure("file_export_cache_unavailable")
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw FileExportFailure("file_export_cache_unavailable")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                    ?: throw FileExportFailure("file_export_busy")
            lock.use {
                checkActive()
                val size = file.data.size
                if (size < 0 || size > limits.maxFileBytes) throw FileExportFailure("file_export_too_large")
                makeRoom(directory, size)
                val output = File.createTempFile("export-", ".bin", directory)
                try {
                    openOutput(output).use { stream ->
                        val chunk = ByteArray(minOf(8192, size))
                        var offset = 0
                        while (offset < size) {
                            checkActive()
                            val count = minOf(chunk.size, size - offset)
                            for (index in 0 until count) chunk[index] = file.data[offset + index].toByte()
                            stream.write(chunk, 0, count)
                            offset += count
                        }
                    }
                    checkActive()
                    val uri =
                        FileProvider
                            .getUriForFile(application, "${application.packageName}.vauchi.display.exports", output, metadata.name)
                            .buildUpon()
                            .appendQueryParameter(ExportFileProvider.MIME, metadata.mime)
                            .build()
                    return PreparedFileExport(output, uri)
                } catch (failure: Exception) {
                    discard(output)
                    throw failure
                }
            }
        }
    }

    private fun makeRoom(
        directory: File,
        size: Int,
    ) {
        val files = directory.listFiles() ?: throw FileExportFailure("file_export_cache_unavailable")
        // Earlier owners can have a larger quota; allow their expired files to retire (#773).
        if (files.size > MAX_EXPORT_CACHE_FILES) throw FileExportFailure("file_export_cache_full")
        val now = clock()
        if (now < 0) throw FileExportFailure("file_export_cache_unavailable")
        var count = 0
        var bytes = 0L
        for (entry in files) {
            if (!ownedName.matches(entry.name) || !entry.isFile || entry.canonicalFile != entry) {
                throw FileExportFailure("file_export_cache_unavailable")
            }
            if (entry.lastModified() <= now - limits.retentionMillis) {
                discard(entry)
            } else {
                val length = entry.length()
                if (length > limits.maxCacheBytes - bytes) throw FileExportFailure("file_export_cache_full")
                bytes += length
                count++
            }
        }
        if (count >= limits.maxFiles || size > limits.maxCacheBytes - bytes) throw FileExportFailure("file_export_cache_full")
    }

    fun discard(file: File) {
        if (file.exists() && !file.delete()) throw FileExportFailure("file_export_cleanup_failed")
    }
}
